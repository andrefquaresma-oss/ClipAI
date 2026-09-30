package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CandidateGoalDiscovery {
    private static final long SCORE_CONFIRMATION_LOOK_AHEAD_MS = 30_000;
    private static final long ACTION_CONTEXT_WINDOW_MS = 20_000;
    private static final double MINIMUM_MODERATE_VOICE_CONFIDENCE = 0.60;
    private static final double MINIMUM_MODERATE_PITCH_CONFIDENCE = 0.60;
    private static final double MINIMUM_SPEECH_RATE_CONFIDENCE = 0.58;
    private final CandidateDetectionQualitySettings settings;

    public CandidateGoalDiscovery(CandidateDetectionQualitySettings settings) {
        this.settings = settings;
    }

    public List<CandidateSignalObservation> discover(List<CandidateSignalObservation> observations) {
        List<CandidateSignal> signals = observations.stream()
                .flatMap(observation -> observation.signals().stream())
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs)
                        .thenComparing(signal -> signal.type().name()))
                .toList();
        List<CandidateSignal> shots = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && signal.eventType() == FootballEventType.SHOT)
                .toList();
        List<CandidateSignalObservation> discovered = new ArrayList<>();
        for (CandidateSignal shot : shots) {
            long actionTimeMs = shot.timestampMs();
            long earliestSupportMs = Math.max(0, actionTimeMs - settings.attackBuildupMaximumLeadMs());
            long reactionEndMs = safeAdd(actionTimeMs, settings.contextAttachWindowMs());
            CandidateSignal buildup = signals.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.ATTACK_BUILDUP
                            && signal.timestampMs() >= earliestSupportMs
                            && signal.timestampMs() < actionTimeMs)
                    .max(Comparator.comparingLong(CandidateSignal::timestampMs)
                            .thenComparingDouble(CandidateSignal::confidence))
                    .orElse(null);
            CandidateSignal audioReaction = signals.stream()
                    .filter(signal -> isAudioReaction(signal.type())
                            && signal.timestampMs() >= actionTimeMs
                            && signal.timestampMs() <= reactionEndMs)
                    .max(Comparator.comparingDouble(CandidateSignal::confidence)
                            .thenComparingLong(signal -> -signal.timestampMs()))
                    .orElse(null);
            CandidateSignal commentaryReaction = signals.stream()
                    .filter(signal -> isCommentaryReaction(signal)
                            && signal.timestampMs() >= actionTimeMs
                            && signal.timestampMs() <= reactionEndMs)
                    .max(Comparator.comparingDouble(CandidateSignal::confidence)
                            .thenComparingLong(signal -> -signal.timestampMs()))
                    .orElse(null);
            double speechRateConfidence = signals.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE
                            && signal.timestampMs() >= actionTimeMs
                            && signal.timestampMs() <= reactionEndMs)
                    .mapToDouble(CandidateSignal::confidence)
                    .max().orElse(0);

            if (audioReaction == null
                    || audioReaction.confidence() < settings.minimumGoalReactionConfidence()
                    || hasGoalKickContext(signals, Math.max(0, actionTimeMs - 3_000), reactionEndMs)
                    || hasNonGoalOutcome(signals, actionTimeMs, reactionEndMs)) {
                continue;
            }
            if (buildup == null && commentaryReaction == null && speechRateConfidence < 0.58) {
                continue;
            }
            String evidence = "Inferred goal hypothesis from a shot, independent audio reaction, and "
                    + (buildup != null ? "attack buildup" : commentaryReaction != null
                    ? "post-action commentary" : "speech-rate increase");
            double corroboration = Math.min(audioReaction.confidence(),
                    Math.max(buildup == null ? 0 : buildup.confidence(),
                            Math.max(commentaryReaction == null ? 0 : commentaryReaction.confidence(),
                                    speechRateConfidence)));
            double confidence = Math.min(0.88, 0.76 + 0.12 * corroboration);
            List<CandidateSignal> goalSignals = new ArrayList<>(List.of(
                    shot,
                    audioReaction,
                    new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD, FootballEventType.GOAL,
                            confidence, actionTimeMs, evidence),
                    new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                            confidence, actionTimeMs, evidence)));
            if (buildup != null) {
                goalSignals.add(buildup);
            }
            if (commentaryReaction != null) {
                goalSignals.add(commentaryReaction);
            }
            discovered.add(new CandidateSignalObservation(actionTimeMs, goalSignals));
        }
        List<CandidateSignalObservation> shotAndReactionGoals = List.copyOf(discovered);
        for (CandidateSignalObservation audioGoal : discoverAudioBackedGoals(signals)) {
            boolean alreadyDiscovered = shotAndReactionGoals.stream().anyMatch(existing ->
                    Math.abs(existing.timestampMs() - audioGoal.timestampMs()) <= ACTION_CONTEXT_WINDOW_MS
                            && existing.signals().stream().anyMatch(signal ->
                            signal.eventType() == FootballEventType.GOAL));
            if (!alreadyDiscovered) {
                discovered.add(audioGoal);
            }
        }
        return List.copyOf(discovered);
    }

    private List<CandidateSignalObservation> discoverAudioBackedGoals(List<CandidateSignal> signals) {
        List<CandidateSignal> reactionAnchors = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.HIGH_EXCITEMENT
                        || signal.type() == CandidateSignalType.VOICE_EXCITEMENT
                        || signal.type() == CandidateSignalType.PITCH_VARIANCE
                        || signal.type() == CandidateSignalType.PITCH_RISE)
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs))
                .toList();
        List<CandidateSignalObservation> discovered = new ArrayList<>();
        for (CandidateSignal reactionAnchor : reactionAnchors) {
            long actionStartMs = Math.max(0, reactionAnchor.timestampMs() - ACTION_CONTEXT_WINDOW_MS);
            long actionEndMs = safeAdd(reactionAnchor.timestampMs(), ACTION_CONTEXT_WINDOW_MS);
            List<CandidateSignal> nearby = signals.stream()
                    .filter(signal -> Math.abs(signal.timestampMs() - reactionAnchor.timestampMs())
                            <= ACTION_CONTEXT_WINDOW_MS)
                    .toList();
            double voiceConfidence = nearby.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.VOICE_EXCITEMENT
                            || signal.type() == CandidateSignalType.HIGH_EXCITEMENT)
                    .mapToDouble(CandidateSignal::confidence).max().orElse(0);
            double pitchConfidence = nearby.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.PITCH_VARIANCE
                            || signal.type() == CandidateSignalType.PITCH_RISE)
                    .mapToDouble(CandidateSignal::confidence).max().orElse(0);
            double speechRateConfidence = nearby.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE)
                    .mapToDouble(CandidateSignal::confidence).max().orElse(0);
            double energyRiseConfidence = nearby.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE)
                    .mapToDouble(CandidateSignal::confidence).max().orElse(0);
            boolean moderateIndependentReaction = voiceConfidence >= MINIMUM_MODERATE_VOICE_CONFIDENCE
                    && (pitchConfidence >= MINIMUM_MODERATE_PITCH_CONFIDENCE
                    || speechRateConfidence >= MINIMUM_SPEECH_RATE_CONFIDENCE
                    || energyRiseConfidence >= 0.65);
            CandidateSignal action = signals.stream()
                    .filter(CandidateGoalDiscovery::isFootballAction)
                    .filter(signal -> signal.confidence() >= 0.55
                            && signal.timestampMs() >= actionStartMs
                            && signal.timestampMs() <= actionEndMs)
                    .min(Comparator.comparingLong((CandidateSignal signal) ->
                                    Math.abs(signal.timestampMs() - reactionAnchor.timestampMs()))
                            .thenComparing(Comparator.comparingDouble(CandidateSignal::confidence).reversed()))
                    .orElse(null);
            CandidateSignal scoreTransition = signals.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                            && signal.timestampMs() >= reactionAnchor.timestampMs()
                            && signal.timestampMs() - reactionAnchor.timestampMs()
                            <= SCORE_CONFIRMATION_LOOK_AHEAD_MS)
                    .min(Comparator.comparingLong(CandidateSignal::timestampMs))
                    .orElse(null);
            boolean hasScoreAndReaction = scoreTransition != null
                    && (voiceConfidence >= MINIMUM_MODERATE_VOICE_CONFIDENCE
                    || pitchConfidence >= MINIMUM_MODERATE_PITCH_CONFIDENCE)
                    && (moderateIndependentReaction
                    || voiceConfidence >= 0.72 && action != null);
            if (!moderateIndependentReaction && !hasScoreAndReaction) {
                continue;
            }
            if (action == null && !hasScoreAndReaction
                    || hasGoalKickContext(signals,
                    Math.max(0, reactionAnchor.timestampMs() - settings.contextAttachWindowMs()),
                    safeAdd(reactionAnchor.timestampMs(), settings.contextAttachWindowMs()))
                    || hasNonGoalOutcome(signals,
                    Math.max(0, reactionAnchor.timestampMs() - settings.contextAttachWindowMs()),
                    safeAdd(reactionAnchor.timestampMs(), settings.contextAttachWindowMs()))) {
                continue;
            }
            double corroboration = Math.max(voiceConfidence, Math.max(pitchConfidence,
                    Math.max(speechRateConfidence, energyRiseConfidence)));
            double confidence = Math.min(0.92, 0.76 + 0.12 * corroboration);
            String evidence = String.format(java.util.Locale.ROOT,
                    "Audio-backed GOAL hypothesis combines voice %.2f, pitch %.2f, speech rate %.2f, "
                            + "and energy rise %.2f with %s",
                    voiceConfidence, pitchConfidence, speechRateConfidence, energyRiseConfidence,
                    action == null ? "a later score-state transition"
                            : "typed football-action evidence");
            List<CandidateSignal> goalSignals = new ArrayList<>(List.of(
                    new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                            FootballEventType.GOAL, confidence, reactionAnchor.timestampMs(), evidence),
                    new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION,
                            FootballEventType.GOAL, confidence,
                            scoreTransition != null || action == null
                                    ? reactionAnchor.timestampMs() : action.timestampMs(),
                            action == null
                                    ? "Event action is anchored to moderate independent audio reaction and "
                                    + "later score transition"
                                    : "Goal action is anchored to typed football-action evidence at "
                                    + action.timestampMs() + " ms")));
            nearby.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.VOICE_EXCITEMENT
                            || signal.type() == CandidateSignalType.HIGH_EXCITEMENT
                            || signal.type() == CandidateSignalType.PITCH_VARIANCE
                            || signal.type() == CandidateSignalType.PITCH_RISE
                            || signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE
                            || signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE)
                    .forEach(goalSignals::add);
            if (action != null) {
                goalSignals.add(action);
            }
            if (scoreTransition != null) {
                goalSignals.add(scoreTransition);
            }
            discovered.add(new CandidateSignalObservation(reactionAnchor.timestampMs(), goalSignals));
        }
        return discovered;
    }

    private static boolean isFootballAction(CandidateSignal signal) {
        if (signal.type() == CandidateSignalType.ATTACK_BUILDUP
                || signal.type() == CandidateSignalType.TRANSCRIPT_SHOT) {
            return true;
        }
        if (signal.type() != CandidateSignalType.TRANSCRIPT_EVENT) {
            return signal.type() == CandidateSignalType.OWN_GOAL
                    && signal.eventType() == FootballEventType.GOAL;
        }
        return signal.eventType() == FootballEventType.ATTACK
                || signal.eventType() == FootballEventType.COUNTER_ATTACK
                || signal.eventType() == FootballEventType.BIG_CHANCE
                || signal.eventType() == FootballEventType.SHOT
                || signal.eventType() == FootballEventType.PENALTY;
    }

    private static boolean isAudioReaction(CandidateSignalType type) {
        return type == CandidateSignalType.AUDIO_SPIKE
                || type == CandidateSignalType.AUDIO_SUSTAINED
                || type == CandidateSignalType.HIGH_EXCITEMENT
                || type == CandidateSignalType.VOICE_EXCITEMENT
                || type == CandidateSignalType.PITCH_RISE
                || type == CandidateSignalType.PITCH_VARIANCE
                || type == CandidateSignalType.CROWD_REACTION_PROXY;
    }

    private static boolean isCommentaryReaction(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.TRANSCRIPT_EMPHASIS
                || signal.type() == CandidateSignalType.TRANSCRIPT_REPETITION
                || signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                && signal.eventType() == FootballEventType.CELEBRATION;
    }

    private static boolean hasGoalKickContext(List<CandidateSignal> signals,
                                              long earliestSupportMs, long reactionEndMs) {
        return signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.GOAL_KICK_CONTEXT
                && signal.timestampMs() >= earliestSupportMs
                && signal.timestampMs() <= reactionEndMs);
    }

    private static boolean hasNonGoalOutcome(List<CandidateSignal> signals,
                                             long startTimeMs, long endTimeMs) {
        return signals.stream().anyMatch(signal -> signal.timestampMs() >= startTimeMs
                && signal.timestampMs() <= endTimeMs
                && (signal.eventType() == FootballEventType.SAVE
                || signal.eventType() == FootballEventType.NEAR_MISS
                || signal.eventType() == FootballEventType.MISSED_PENALTY
                || signal.type() == CandidateSignalType.SHOT_OUTCOME_CONTEXT
                && (signal.evidence().contains("OUTCOME=SAVE")
                || signal.evidence().contains("OUTCOME=BLOCK")
                || signal.evidence().contains("OUTCOME=CLEARANCE")
                || signal.evidence().contains("OUTCOME=WIDE")
                || signal.evidence().contains("OUTCOME=POST")
                || signal.evidence().contains("OUTCOME=CONTINUED_PLAY"))));
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
