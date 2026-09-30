package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateScoreComponent;
import com.clipai.domain.candidate.CandidateScoreComponentType;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CandidateEventAssembler {
    private static final int MAXIMUM_CONTEXT_LENGTH = 6000;
    private static final double MINIMUM_REPLAY_CONFIRMED_ATTACK_RATE = 0.58;
    private static final Set<String> REPLAY_IDENTITY_STOP_WORDS = Set.of(
            "goal", "goals", "score", "scores", "scored", "replay", "goalcall", "crowd",
            "team", "match", "but", "marque", "marquer", "egalisation", "le", "la",
            "les", "des", "une", "the", "and", "for", "after", "from", "into", "dans",
            "pour", "avec", "sur", "qui", "que", "est");
    private final CandidateDetectionSettings settings;
    private final CandidateDetectionQualitySettings qualitySettings;

    public CandidateEventAssembler(CandidateDetectionSettings settings) {
        this(settings, CandidateDetectionQualitySettings.defaults());
    }

    public CandidateEventAssembler(CandidateDetectionSettings settings,
                                   CandidateDetectionQualitySettings qualitySettings) {
        this.settings = settings;
        this.qualitySettings = qualitySettings;
    }

    public List<CandidateEvent> assemble(UUID mediaAssetId, Transcript transcript,
                                         List<CandidateSignalObservation> observations,
                                         long durationMs, Instant createdAt) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        List<CandidateSignalObservation> ordered = observations.stream()
                .filter(observation -> observation.timestampMs() <= durationMs)
                .sorted(Comparator.comparingLong(CandidateSignalObservation::timestampMs))
                .toList();
        List<CandidateSignal> allSignals = ordered.stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.timestampMs() <= durationMs)
                .distinct()
                .toList();
        List<AnchorCluster> anchors = reconstructReplayConfirmedLiveGoals(transcript,
                eventAnchors(ordered), allSignals);
        List<CandidateEvent> chronological = new ArrayList<>();
        for (AnchorCluster anchor : anchors) {
            toCandidate(mediaAssetId, transcript, anchor, anchors, allSignals, chronological,
                    durationMs, createdAt).ifPresent(chronological::add);
        }
        return chronological.stream()
                .sorted(Comparator.comparing((CandidateEvent candidate) ->
                                candidate.status() != CandidateEventStatus.DETECTED)
                        .thenComparing(Comparator.comparingDouble(CandidateEvent::score).reversed())
                        .thenComparingLong(CandidateEvent::startTimeMs)
                        .thenComparing(candidate -> candidate.eventType().name()))
                .limit(settings.maximumCandidates())
                .toList();
    }

    private List<AnchorCluster> reconstructReplayConfirmedLiveGoals(
            Transcript transcript, List<AnchorCluster> anchors, List<CandidateSignal> allSignals) {
        Set<AnchorCluster> consumed = new HashSet<>();
        List<AnchorCluster> reconstructed = new ArrayList<>();
        List<AnchorCluster> establishedGoals = anchors.stream()
                .filter(anchor -> anchor.eventType == FootballEventType.GOAL)
                .toList();

        for (AnchorCluster replayGoal : establishedGoals) {
            if (consumed.contains(replayGoal)) {
                continue;
            }
            CandidateSignal scoringPhrase = replayGoal.signals.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                            && signal.eventType() == FootballEventType.GOAL
                            && signal.confidence() >= settings.singleSignalConfidenceThreshold()
                            && signal.evidence().startsWith("Football phrase match: "))
                    .max(Comparator.comparingDouble(CandidateSignal::confidence))
                    .orElse(null);
            if (scoringPhrase == null) {
                continue;
            }

            CandidateSignal replayCue = allSignals.stream()
                    .filter(signal -> (signal.type() == CandidateSignalType.REPLAY_CONTEXT
                            || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT)
                            && signal.confidence() >= qualitySettings.replayProbabilityThreshold()
                            && signal.evidence().startsWith("Context phrase or structure match:")
                            && signal.timestampMs() >= replayGoal.firstTimestampMs
                            - qualitySettings.contextAttachWindowMs()
                            && signal.timestampMs() <= replayGoal.firstTimestampMs)
                    .max(Comparator.comparingDouble(CandidateSignal::confidence))
                    .orElse(null);
            if (replayCue == null) {
                continue;
            }

            String replayContext = goalReviewContext(transcript, replayGoal.firstTimestampMs);
            if (hasEstablishedSameActionGoal(transcript, replayGoal, establishedGoals, replayContext)) {
                continue;
            }

            AnchorCluster liveAttack = anchors.stream()
                    .filter(anchor -> anchor.eventType == FootballEventType.ATTACK
                            && !consumed.contains(anchor)
                            && anchor.firstTimestampMs < replayGoal.firstTimestampMs
                            && replayGoal.firstTimestampMs - anchor.firstTimestampMs
                            <= qualitySettings.replayTemporalWindowMs())
                    .filter(anchor -> hasReplayConfirmedAttackEvidence(anchor, allSignals))
                    .filter(anchor -> !hasScoreTransitionBetween(allSignals,
                            anchor.firstTimestampMs, replayGoal.firstTimestampMs))
                    .filter(anchor -> !hasInjuryBetween(allSignals,
                            anchor.firstTimestampMs, replayGoal.firstTimestampMs))
                    .filter(anchor -> anchors.stream().noneMatch(other ->
                            other != replayGoal && other.eventType == FootballEventType.GOAL
                                    && other.firstTimestampMs > anchor.firstTimestampMs
                                    && other.firstTimestampMs < replayGoal.firstTimestampMs))
                    .filter(anchor -> {
                        String attackContext = goalReviewContext(transcript, anchor.firstTimestampMs);
                        return contextSimilarity(attackContext, replayContext) >= 0.10
                                || sharedReplayIdentityTokens(attackContext, replayContext) >= 2;
                    })
                    .max(Comparator.comparingLong(anchor -> anchor.firstTimestampMs))
                    .orElse(null);
            if (liveAttack == null) {
                continue;
            }

            long actionTimestampMs = liveAttack.firstTimestampMs;
            String confirmation = "RETROSPECTIVE_GOAL_CONFIRMATION: a nearby attack with a speech-rate increase "
                    + "is corroborated by an explicit replay introduction and scoring phrase at "
                    + replayGoal.firstTimestampMs + " ms; no score transition or established same-action "
                    + "goal intervenes";
            List<CandidateSignal> signals = new ArrayList<>(liveAttack.signals);
            signals.add(new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION,
                    FootballEventType.GOAL, scoringPhrase.confidence(), actionTimestampMs, confirmation));
            signals.add(new CandidateSignal(CandidateSignalType.EVENT_ASSOCIATION,
                    FootballEventType.GOAL, replayCue.confidence(), actionTimestampMs,
                    "REPLAY_EVIDENCE_FOR_LIVE_EVENT; explicit replay cue at " + replayCue.timestampMs()
                            + " ms and scoring phrase at " + scoringPhrase.timestampMs()
                            + " ms corroborate this preceding live attack; replay audio is not used as live reaction"));
            reconstructed.add(new AnchorCluster(FootballEventType.GOAL, actionTimestampMs, signals));
            consumed.add(liveAttack);
            consumed.add(replayGoal);
        }

        if (reconstructed.isEmpty()) {
            return anchors;
        }
        List<AnchorCluster> result = new ArrayList<>();
        anchors.stream().filter(anchor -> !consumed.contains(anchor)).forEach(result::add);
        result.addAll(reconstructed);
        return result.stream()
                .sorted(Comparator.comparingLong((AnchorCluster anchor) -> anchor.firstTimestampMs)
                        .thenComparing(anchor -> anchor.eventType.name()))
                .toList();
    }

    private boolean hasReplayConfirmedAttackEvidence(AnchorCluster attack, List<CandidateSignal> allSignals) {
        boolean transcriptAttack = attack.signals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                        && signal.eventType() == FootballEventType.ATTACK
                        && signal.confidence() >= settings.confidenceFor(FootballEventType.ATTACK));
        boolean risingSpeech = allSignals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE
                        && Math.abs(signal.timestampMs() - attack.firstTimestampMs)
                        <= qualitySettings.contextAttachWindowMs()
                        && signal.confidence() >= MINIMUM_REPLAY_CONFIRMED_ATTACK_RATE);
        return transcriptAttack && risingSpeech;
    }

    private static boolean hasScoreTransitionBetween(List<CandidateSignal> signals,
                                                     long startTimestampMs, long endTimestampMs) {
        return signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                        && signal.timestampMs() >= startTimestampMs
                        && signal.timestampMs() <= endTimestampMs);
    }

    private static boolean hasInjuryBetween(List<CandidateSignal> signals,
                                            long startTimestampMs, long endTimestampMs) {
        return signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.INJURY_CONTEXT
                        && signal.timestampMs() >= startTimestampMs
                        && signal.timestampMs() <= endTimestampMs);
    }

    private boolean hasEstablishedSameActionGoal(Transcript transcript, AnchorCluster replayGoal,
                                                 List<AnchorCluster> goalAnchors, String replayContext) {
        return goalAnchors.stream()
                .filter(anchor -> anchor != replayGoal
                        && anchor.firstTimestampMs < replayGoal.firstTimestampMs)
                .map(anchor -> goalReviewContext(transcript, anchor.firstTimestampMs))
                .anyMatch(goalContext -> goalContext != null
                        && replayContext != null
                        && Math.max(contextSimilarity(goalContext, replayContext),
                        contextContainment(goalContext, replayContext)) >= 0.70
                        && sharedReplayIdentityTokens(goalContext, replayContext) >= 3);
    }

    private String goalReviewContext(Transcript transcript, long timestampMs) {
        long radius = qualitySettings.maximumCandidateDurationMs() / 2;
        return transcriptContext(transcript, Math.max(0, timestampMs - radius),
                safeAdd(timestampMs, radius));
    }

    private List<AnchorCluster> eventAnchors(List<CandidateSignalObservation> observations) {
        Map<AnchorKey, LinkedHashSet<CandidateSignal>> grouped = new HashMap<>();
        for (CandidateSignalObservation observation : observations) {
            for (CandidateSignal signal : observation.signals()) {
                if (!isEventSpecificSignal(signal.type(), signal.eventType())) {
                    continue;
                }
                AnchorKey key = new AnchorKey(signal.eventType(), signal.timestampMs());
                LinkedHashSet<CandidateSignal> signals =
                        grouped.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                signals.add(signal);
                observation.signals().stream()
                        .filter(related -> related.eventType() == signal.eventType()
                                && related.timestampMs() == signal.timestampMs()
                                && related.type() == CandidateSignalType.TRANSCRIPT_KEYWORD)
                        .forEach(signals::add);
            }
        }

        List<Map.Entry<AnchorKey, LinkedHashSet<CandidateSignal>>> ordered = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparingLong(AnchorKey::timestampMs)
                        .thenComparing(key -> key.eventType().name())))
                .toList();
        return ordered.stream()
                .map(entry -> new AnchorCluster(entry.getKey().eventType(), entry.getKey().timestampMs(),
                        new ArrayList<>(entry.getValue())))
                .toList();
    }

    private Optional<CandidateEvent> toCandidate(UUID mediaAssetId, Transcript transcript,
                                                  AnchorCluster anchor, List<AnchorCluster> allAnchors,
                                                  List<CandidateSignal> allSignals,
                                                  List<CandidateEvent> priorCandidates,
                                                  long durationMs, Instant createdAt) {
        long triggerTimestampMs = chooseTrigger(anchor.signals);
        if (triggerTimestampMs < 0) {
            return Optional.empty();
        }

        List<CandidateSignal> signals = new ArrayList<>(anchor.signals);
        for (CandidateSignal signal : allSignals) {
            if (isRelevantSupport(signal, triggerTimestampMs, allAnchors, anchor)
                    && !signals.contains(signal)) {
                signals.add(signal);
            }
        }

        FootballEventType eventType = anchor.eventType;
        CandidateSignal reconstructedAction = eventType == FootballEventType.GOAL
                ? reconstructGoalAction(triggerTimestampMs, allSignals, allAnchors, anchor)
                : null;
        AssociatedFoul precedingFoul = eventType == FootballEventType.PENALTY
                || eventType == FootballEventType.YELLOW_CARD || eventType == FootballEventType.RED_CARD
                ? findAssociatedFoul(allSignals, transcript, triggerTimestampMs) : null;
        if (reconstructedAction != null) {
            signals.add(reconstructedAction);
        }
        if (precedingFoul != null) {
            signals.add(new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, eventType,
                    precedingFoul.contextSimilarity(), precedingFoul.signal().timestampMs(),
                    eventType + " sequence anchored to the preceding contextual FOUL cue at "
                            + precedingFoul.signal().timestampMs() + " ms"));
        }
        double directEventConfidence = directEventConfidence(signals, eventType);
        double audioConfidence = audioConfidence(signals);
        double textualReactionConfidence = textualReactionConfidence(signals);
        double speechRateConfidence = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
        double celebrationConfidence = signals.stream()
                .filter(signal -> signal.eventType() == FootballEventType.CELEBRATION
                        && isTranscriptSignal(signal.type()))
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
        double reactionConfidence = Math.max(audioConfidence,
                Math.max(textualReactionConfidence, Math.max(speechRateConfidence * 0.25,
                        celebrationConfidence * 0.75)));
        List<CandidateSignal> goalContextSignals = eventType == FootballEventType.GOAL
                ? signals.stream()
                .filter(CandidateEventAssembler::isGoalNegativeContext)
                .filter(signal -> signal.confidence() >= 0.68)
                .filter(signal -> signal.timestampMs() <= triggerTimestampMs
                        && triggerTimestampMs - signal.timestampMs()
                        <= Math.max(qualitySettings.contextAttachWindowMs(), 3_000))
                .toList() : List.of();
        double goalNegativeContextConfidence = goalContextSignals.stream()
                .mapToDouble(CandidateSignal::confidence).max().orElse(0);
        boolean explicitScoringPhrase = eventType == FootballEventType.GOAL
                && goalContextSignals.isEmpty() && hasExplicitScoringPhrase(signals);
        double buildupConfidence = buildupConfidence(signals);
        double replayProbability = replayProbability(signals);
        String localContext = transcriptContext(transcript,
                Math.max(0, triggerTimestampMs - qualitySettings.contextAttachWindowMs()),
                Math.min(durationMs, triggerTimestampMs + qualitySettings.contextAttachWindowMs()));
        boolean explicitReplayContext = eventType == FootballEventType.GOAL
                && replayProbability >= qualitySettings.replayProbabilityThreshold();
        boolean replayContext = explicitReplayContext && hasStrongReplayAssociation(
                triggerTimestampMs, localContext, signals, priorCandidates);
        boolean injuryThenReplay = replayContext && hasInjuryThenReplay(signals, triggerTimestampMs);
        boolean postRestart = eventType == FootballEventType.GOAL
                && signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.RESTART_CONTEXT
                && signal.timestampMs() < triggerTimestampMs
                && triggerTimestampMs - signal.timestampMs() <= 30_000);
        boolean scoreTransitionCorroborates = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                        && (!postRestart || signal.timestampMs() >= signals.stream()
                        .filter(restartSignal -> restartSignal.type() == CandidateSignalType.RESTART_CONTEXT
                                && restartSignal.timestampMs() < triggerTimestampMs)
                        .mapToLong(CandidateSignal::timestampMs).max().orElse(Long.MIN_VALUE))
                        && signal.timestampMs() <= safeAdd(triggerTimestampMs, 30_000));
        boolean postRestartWithoutScoreTransition = postRestart && !scoreTransitionCorroborates;
        boolean penaltyAttemptCorroborates = eventType == FootballEventType.GOAL
                && hasPenaltyAttemptCorroboration(transcript, triggerTimestampMs, signals,
                reactionConfidence);
        boolean orderedLiveEvidenceCorroborates = eventType == FootballEventType.GOAL
                && hasOrderedLiveEvidence(signals, triggerTimestampMs, reactionConfidence,
                textualReactionConfidence);
        boolean retrospectiveReference = eventType == FootballEventType.GOAL
                && (goalContextSignals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                        || signal.type() == CandidateSignalType.HISTORICAL_REFERENCE)
                || hasNearbyRetrospectiveReference(signals, triggerTimestampMs));
        boolean negatedGoal = goalContextSignals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.NEGATION_CONTEXT);
        boolean correctedGoal = goalContextSignals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.CORRECTION_CONTEXT);
        boolean historicalGoal = goalContextSignals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.HISTORICAL_REFERENCE);
        boolean replayCommentary = goalContextSignals.stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.REPLAY_CONTEXT);
        boolean setPieceShotWithoutScoring = eventType == FootballEventType.GOAL
                && hasSetPieceShotWithoutScoring(transcript, triggerTimestampMs, signals);
        boolean unconfirmedGoalHypothesis = eventType == FootballEventType.GOAL
                && hasInferredGoalHypothesis(signals)
                && !explicitScoringPhrase && !scoreTransitionCorroborates
                && !hasRetrospectiveGoalConfirmation(signals)
                && !penaltyAttemptCorroborates && !orderedLiveEvidenceCorroborates;

        double liveProbability = clamp(0.20 + 0.45 * directEventConfidence
                + 0.25 * audioConfidence + 0.08 * textualReactionConfidence
                + 0.07 * buildupConfidence
                - qualitySettings.replayPenaltyWeight() * (replayContext ? replayProbability : 0)
                - qualitySettings.goalNegativeContextPenaltyWeight() * goalNegativeContextConfidence);
        double totalWeight = settings.transcriptWeight() + settings.audioWeight();
        double score = (directEventConfidence * settings.transcriptWeight()
                + audioConfidence * settings.audioWeight()) / totalWeight;
        if (directEventConfidence > 0 && audioConfidence > 0) {
            score += settings.signalAgreementBonus() * 0.25;
        }
        score += 0.05 * buildupConfidence
                - qualitySettings.replayPenaltyWeight() * (replayContext ? replayProbability : 0) * 0.5
                - qualitySettings.goalNegativeContextPenaltyWeight() * goalNegativeContextConfidence;
        score = Math.min(0.98, clamp(score));
        List<CandidateScoreComponent> scoreContributions = scoreContributions(signals, eventType,
                directEventConfidence, audioConfidence, buildupConfidence,
                replayContext ? replayProbability : 0, goalNegativeContextConfidence, score, totalWeight);

        List<String> rejectionReasons = new ArrayList<>();
        if (replayContext) {
            rejectionReasons.add("REPLAY_OF_EXISTING_GOAL");
        }
        if (replayCommentary) {
            rejectionReasons.add("REPLAY_COMMENTARY_REFERENCE");
        }
        if (negatedGoal) {
            rejectionReasons.add("NEGATED_GOAL_REFERENCE");
        }
        if (correctedGoal) {
            rejectionReasons.add("CORRECTED_GOAL_REFERENCE");
        }
        if (historicalGoal) {
            rejectionReasons.add("HISTORICAL_GOAL_REFERENCE");
        }
        if (injuryThenReplay) {
            rejectionReasons.add("INJURY_THEN_REPLAY");
        }
        if (postRestartWithoutScoreTransition && replayContext) {
            rejectionReasons.add("POST_RESTART");
            rejectionReasons.add("NO_NEW_SCORE_TRANSITION");
        }
        if (retrospectiveReference && !explicitScoringPhrase && !scoreTransitionCorroborates) {
            rejectionReasons.add("RETROSPECTIVE_GOAL_REFERENCE");
        }
        if (unconfirmedGoalHypothesis) {
            rejectionReasons.add("GOAL_HYPOTHESIS_UNCONFIRMED");
        }
        if (unconfirmedGoalHypothesis && setPieceShotWithoutScoring) {
            rejectionReasons.add("SET_PIECE_SHOT_WITHOUT_SCORING_CONFIRMATION");
        }
        if (eventType == FootballEventType.GOAL
                && !explicitScoringPhrase && !scoreTransitionCorroborates
                && !penaltyAttemptCorroborates && hasShotFollowedByContinuedPlay(
                signals, triggerTimestampMs)) {
            rejectionReasons.add("NON_SCORING_SHOT_CONTINUED_PLAY");
        }
        double requiredEventEvidence = eventType == FootballEventType.ATTACK
                ? settings.confidenceFor(eventType)
                : settings.singleSignalConfidenceThreshold();
        if (directEventConfidence < requiredEventEvidence) {
            rejectionReasons.add("INSUFFICIENT_EVENT_SPECIFIC_EVIDENCE");
        }
        if (eventType == FootballEventType.GOAL
                && reactionConfidence < qualitySettings.minimumGoalReactionConfidence()
                && !explicitScoringPhrase) {
            rejectionReasons.add("GOAL_WITHOUT_REACTION_CORROBORATION");
        }
        if (eventType == FootballEventType.ATTACK
                && (buildupConfidence < 0.70 || audioConfidence < 0.55)) {
            rejectionReasons.add("ATTACK_WITHOUT_LOCAL_BUILDUP_AND_REACTION");
        }
        if (liveProbability < qualitySettings.minimumLiveEventProbability()) {
            rejectionReasons.add("LOW_LIVE_EVENT_PROBABILITY");
        }
        if (score < settings.minimumCandidateScore()) {
            rejectionReasons.add("LOW_CANDIDATE_SCORE");
        }

        CandidateSignal boundaryAction = reconstructedAction;
        if (boundaryAction == null && precedingFoul != null) {
            boundaryAction = new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, eventType,
                    precedingFoul.contextSimilarity(), precedingFoul.signal().timestampMs(),
                    eventType + " action is reconstructed from its temporally and contextually linked foul");
        }
        long boundaryReferenceMs = boundaryAction == null
                ? triggerTimestampMs : boundaryAction.timestampMs();
        Boundary boundary = estimateBoundary(eventType, triggerTimestampMs, boundaryReferenceMs,
                boundaryAction != null, signals, allSignals, transcript, allAnchors, anchor, durationMs);
        long startTimeMs = boundary.startTimeMs();
        long endTimeMs = boundary.endTimeMs();
        signals.removeIf(signal -> signal.timestampMs() < startTimeMs || signal.timestampMs() > endTimeMs);
        signals.add(new CandidateSignal(CandidateSignalType.LIVE_EVENT_CONTEXT, eventType,
                liveProbability, triggerTimestampMs,
                goalNegativeContextConfidence > 0
                        ? String.format(java.util.Locale.ROOT,
                        "CLASSIFICATION=SUPPRESSED_GOAL; direct evidence %.2f, contextual negative evidence %.2f",
                        directEventConfidence, goalNegativeContextConfidence)
                        : String.format(java.util.Locale.ROOT,
                        "CLASSIFICATION=LIVE_EVENT; live score uses event-specific transcript %.2f, independent audio reaction %.2f, "
                                + "buildup %.2f and replay penalty %.2f",
                        directEventConfidence, audioConfidence, buildupConfidence,
                        replayContext ? replayProbability : 0)));
        if (explicitReplayContext) {
            signals.add(new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, eventType,
                    replayProbability, triggerTimestampMs,
                    replayContext
                            ? "CLASSIFICATION=REPLAY_OF_EXISTING_GOAL; explicit replay context and same-action evidence match an established live goal"
                            : "REPLAY/RETROSPECTIVE_CONTEXT; no established live-goal association; contextual suppression applied"));
        }
        if (postRestartWithoutScoreTransition && replayContext) {
            signals.add(new CandidateSignal(CandidateSignalType.POST_EVENT_COMMENTARY, eventType,
                    replayProbability, triggerTimestampMs,
                    "CLASSIFICATION=POST_EVENT_COMMENTARY; a restart precedes this replay cue and no new score transition corroborates a live goal"));
            signals.add(new CandidateSignal(CandidateSignalType.UNRELATED_ACTION_CONTEXT, eventType,
                    replayProbability, triggerTimestampMs,
                    "POST_RESTART_NO_NEW_SCORE_TRANSITION; later action is not a new canonical goal"));
        }
        if (injuryThenReplay) {
            signals.add(new CandidateSignal(CandidateSignalType.INJURY_CONTEXT, eventType,
                    0.90, triggerTimestampMs,
                    "INJURY_THEN_REPLAY; medical-attention context precedes replay evidence"));
        }
        if (penaltyAttemptCorroborates) {
            signals.add(new CandidateSignal(CandidateSignalType.EVENT_ASSOCIATION, eventType,
                    0.82, triggerTimestampMs,
                    "PENALTY_ATTEMPT_WITH_INDEPENDENT_REACTION; a nearby penalty context and shot are "
                            + "corroborated by an independent live reaction, with no missed-penalty outcome"));
        }
        if (unconfirmedGoalHypothesis && setPieceShotWithoutScoring) {
            signals.add(new CandidateSignal(CandidateSignalType.UNRELATED_ACTION_CONTEXT, eventType,
                    0.82, triggerTimestampMs,
                    "SET_PIECE_SHOT_WITHOUT_SCORING_CONFIRMATION; the nearby corner sequence contains a shot "
                            + "but no independent evidence confirms a live goal"));
        }
        if (retrospectiveReference && !explicitScoringPhrase && !scoreTransitionCorroborates) {
            signals.add(new CandidateSignal(CandidateSignalType.RETROSPECTIVE_CONTEXT, eventType,
                    nearbyRetrospectiveConfidence(signals, triggerTimestampMs,
                            qualitySettings.maximumCandidateDurationMs() / 2), triggerTimestampMs,
                    "RETROSPECTIVE_GOAL_REFERENCE; the nearby goal wording describes a remembered or "
                            + "previous event, not a new scoring action"));
        }
        signals.add(new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, eventType,
                boundary.startConfidence(), startTimeMs, boundary.startReason()));
        signals.add(new CandidateSignal(CandidateSignalType.EVENT_AFTERGLOW, eventType,
                boundary.endConfidence(), endTimeMs, boundary.endReason()));
        for (String reason : rejectionReasons) {
            signals.add(new CandidateSignal(CandidateSignalType.REJECTION_REASON, eventType,
                    Math.max(0.5, 1 - liveProbability), triggerTimestampMs, reason));
        }
        if (precedingFoul != null) {
            signals.add(new CandidateSignal(CandidateSignalType.EVENT_ASSOCIATION, eventType,
                    precedingFoul.contextSimilarity(), triggerTimestampMs,
                    eventType + " associated with preceding FOUL cue at "
                            + precedingFoul.signal().timestampMs()
                            + " ms based on nearby shared transcript context"));
        }

        String context = transcriptContext(transcript, startTimeMs, endTimeMs);
        if (rejectionReasons.isEmpty()) {
            return Optional.of(CandidateEvent.detected(mediaAssetId, startTimeMs, endTimeMs,
                    triggerTimestampMs, eventType, score, signals, context, createdAt, scoreContributions));
        }
        return Optional.of(CandidateEvent.rejected(mediaAssetId, startTimeMs, endTimeMs,
                triggerTimestampMs, eventType, score, signals, context, createdAt, scoreContributions));
    }

    private boolean hasStrongReplayAssociation(long triggerTimestampMs, String context,
                                               List<CandidateSignal> signals,
                                               List<CandidateEvent> priorCandidates) {
        boolean hasNewScoreTransition = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                        && signal.timestampMs() >= triggerTimestampMs
                        && signal.timestampMs() - triggerTimestampMs <= 30_000);
        if (hasNewScoreTransition || context == null || context.isBlank()) {
            return false;
        }
        for (CandidateEvent previous : priorCandidates) {
            if (previous.status() != CandidateEventStatus.DETECTED
                    || previous.eventType() != FootballEventType.GOAL
                    || triggerTimestampMs <= previous.triggerTimestampMs()
                    || previous.liveEventProbability() < qualitySettings.minimumLiveEventProbability()
                    || previous.rejectionReasons().size() > 0) {
                continue;
            }
            if (previous.transcriptContext() != null
                    && Math.max(contextSimilarity(context, previous.transcriptContext()),
                    contextContainment(context, previous.transcriptContext())) >= 0.70
                    && sharedReplayIdentityTokens(context, previous.transcriptContext()) >= 3) {
                return true;
            }
        }
        return false;
    }

    private static int sharedReplayIdentityTokens(String first, String second) {
        Set<String> firstTokens = replayIdentityTokens(first);
        firstTokens.retainAll(replayIdentityTokens(second));
        return firstTokens.size();
    }

    private static double contextContainment(String first, String second) {
        Set<String> firstTokens = replayIdentityTokens(first);
        Set<String> secondTokens = replayIdentityTokens(second);
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) {
            return 0;
        }
        firstTokens.retainAll(secondTokens);
        return (double) firstTokens.size() / Math.min(
                replayIdentityTokens(first).size(), replayIdentityTokens(second).size());
    }

    private static Set<String> replayIdentityTokens(String context) {
        Set<String> tokens = new HashSet<>(Arrays.asList(
                TranscriptTextNormalizer.normalize(context).split("\\s+")));
        tokens.removeIf(token -> token.length() < 4 || REPLAY_IDENTITY_STOP_WORDS.contains(token));
        return tokens;
    }

    private boolean isRelevantSupport(CandidateSignal signal, long triggerTimestampMs,
                                      List<AnchorCluster> allAnchors, AnchorCluster current) {
        long distance = Math.abs(signal.timestampMs() - triggerTimestampMs);
        if (signal.type() == CandidateSignalType.REPLAY_CONTEXT
                || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs() <= qualitySettings.replayTemporalWindowMs();
        }
        if (isGoalNegativeContext(signal)) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs()
                    <= Math.max(qualitySettings.contextAttachWindowMs(), 3_000);
        }
        if (signal.type() == CandidateSignalType.INJURY_CONTEXT) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs() <= qualitySettings.replayTemporalWindowMs();
        }
        if (signal.type() == CandidateSignalType.RESTART_CONTEXT) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs() <= qualitySettings.replayTemporalWindowMs()
                    || signal.timestampMs() > triggerTimestampMs
                    && signal.timestampMs() - triggerTimestampMs <= 30_000;
        }
        if (signal.type() == CandidateSignalType.ATTACK_BUILDUP) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs() <= Math.min(
                    qualitySettings.attackBuildupMaximumLeadMs(), qualitySettings.boundarySearchBackMs())
                    && !hasInterveningAnchor(signal.timestampMs(), triggerTimestampMs, allAnchors, current);
        }
        if (current.eventType == FootballEventType.GOAL
                && signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION) {
            return signal.timestampMs() >= triggerTimestampMs
                    && signal.timestampMs() - triggerTimestampMs <= 30_000;
        }
        if (current.eventType == FootballEventType.GOAL && isGoalActionSupport(signal)) {
            return distance <= 20_000;
        }
        if (current.eventType == FootballEventType.GOAL
                && signal.type() == CandidateSignalType.SHOT_OUTCOME_CONTEXT) {
            return distance <= qualitySettings.contextAttachWindowMs();
        }
        if (signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE) {
            return signal.timestampMs() <= triggerTimestampMs
                    && triggerTimestampMs - signal.timestampMs() <= Math.min(
                    qualitySettings.attackBuildupMaximumLeadMs(), qualitySettings.boundarySearchBackMs())
                    && !hasInterveningAnchor(signal.timestampMs(), triggerTimestampMs, allAnchors, current);
        }
        return isReactionSignal(signal.type())
                && distance <= qualitySettings.contextAttachWindowMs()
                || signal.eventType() == FootballEventType.CELEBRATION
                && isTranscriptSignal(signal.type())
                && distance <= qualitySettings.contextAttachWindowMs();
    }

    private static boolean isGoalActionSupport(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.ATTACK_BUILDUP
                || signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                || signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                && signal.eventType() == FootballEventType.GOAL
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                && (signal.eventType() == FootballEventType.ATTACK
                || signal.eventType() == FootballEventType.COUNTER_ATTACK
                || signal.eventType() == FootballEventType.BIG_CHANCE
                || signal.eventType() == FootballEventType.SHOT
                || signal.eventType() == FootballEventType.PENALTY);
    }

    private CandidateSignal reconstructGoalAction(long triggerTimestampMs, List<CandidateSignal> signals,
                                                  List<AnchorCluster> allAnchors, AnchorCluster current) {
        CandidateSignal retrospectiveAction = signals.stream()
                .filter(CandidateEventAssembler::isRetrospectiveGoalConfirmation)
                .filter(signal -> signal.timestampMs() == triggerTimestampMs)
                .findFirst().orElse(null);
        if (retrospectiveAction != null) {
            return retrospectiveAction;
        }
        long maximumLead = Math.min(qualitySettings.attackBuildupMaximumLeadMs(),
                qualitySettings.boundarySearchBackMs());
        return signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && signal.eventType() == FootballEventType.SHOT
                        && signal.timestampMs() < triggerTimestampMs
                        && triggerTimestampMs - signal.timestampMs() <= maximumLead)
                .filter(signal -> allAnchors.stream().noneMatch(anchor ->
                        anchor != current && anchor.eventType == FootballEventType.GOAL
                                && anchor.firstTimestampMs > signal.timestampMs()
                                && anchor.firstTimestampMs < triggerTimestampMs))
                .max(Comparator.comparingLong(CandidateSignal::timestampMs)
                        .thenComparingDouble(CandidateSignal::confidence))
                .map(signal -> new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION,
                        FootballEventType.GOAL, signal.confidence(), signal.timestampMs(),
                        "Goal action reconstructed from the preceding shot cue at "
                                + signal.timestampMs() + " ms; goal commentary trigger is at "
                                + triggerTimestampMs + " ms"))
                .orElseGet(() -> signals.stream()
                        .filter(signal -> signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                                && signal.eventType() == FootballEventType.GOAL
                                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 20_000
                                        && (signal.evidence().startsWith("Event action is anchored")
                                        || signal.evidence().startsWith("Goal action is anchored")))
                                .min(Comparator.comparingLong(signal ->
                                        Math.abs(signal.timestampMs() - triggerTimestampMs))).orElse(null));
    }

    private AssociatedFoul findAssociatedFoul(List<CandidateSignal> signals, Transcript transcript,
                                              long eventTimestampMs) {
        long associationWindowMs = Math.min(qualitySettings.contextAttachWindowMs() * 2, 15_000);
        String penaltyContext = transcriptContext(transcript,
                Math.max(0, eventTimestampMs - 2_500), safeAdd(eventTimestampMs, 2_500));
        return signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                        && signal.eventType() == FootballEventType.FOUL
                        && signal.timestampMs() <= eventTimestampMs
                        && eventTimestampMs - signal.timestampMs() <= associationWindowMs)
                .map(signal -> {
                    String foulContext = transcriptContext(transcript,
                            Math.max(0, signal.timestampMs() - 2_500), safeAdd(signal.timestampMs(), 2_500));
                    return new AssociatedFoul(signal, contextOverlapSimilarity(foulContext, penaltyContext));
                })
                .filter(association -> association.contextSimilarity() >= 0.25)
                .max(Comparator.comparingLong(association ->
                        -Math.abs(eventTimestampMs - association.signal().timestampMs())))
                .orElse(null);
    }

    private static double contextOverlapSimilarity(String first, String second) {
        Set<String> firstTokens = new HashSet<>(Arrays.asList(
                TranscriptTextNormalizer.normalize(first == null ? "" : first).split("\\s+")));
        Set<String> secondTokens = new HashSet<>(Arrays.asList(
                TranscriptTextNormalizer.normalize(second == null ? "" : second).split("\\s+")));
        firstTokens.removeIf(String::isBlank);
        secondTokens.removeIf(String::isBlank);
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) {
            return 0;
        }
        firstTokens.retainAll(secondTokens);
        return (double) firstTokens.size() / Math.min(
                contextWordCount(first), contextWordCount(second));
    }

    private static int contextWordCount(String context) {
        if (context == null || context.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(TranscriptTextNormalizer.normalize(context).split("\\s+"))
                .filter(word -> !word.isBlank())
                .count();
    }

    private static boolean hasInterveningAnchor(long startTimeMs, long endTimeMs,
                                                List<AnchorCluster> anchors, AnchorCluster current) {
        long start = Math.min(startTimeMs, endTimeMs);
        long end = Math.max(startTimeMs, endTimeMs);
        return anchors.stream()
                .filter(anchor -> anchor != current)
                .anyMatch(anchor -> anchor.firstTimestampMs > start && anchor.firstTimestampMs < end);
    }

    private Boundary estimateBoundary(FootballEventType eventType, long triggerTimestampMs,
                                       long boundaryReferenceMs, boolean reconstructedAction,
                                       List<CandidateSignal> signals, List<CandidateSignal> allSignals,
                                       Transcript transcript, List<AnchorCluster> allAnchors,
                                       AnchorCluster current, long durationMs) {
        long maximumLead = Math.min(qualitySettings.attackBuildupMaximumLeadMs(),
                qualitySettings.boundarySearchBackMs());
        long searchStartMs = Math.max(0, boundaryReferenceMs - maximumLead);
        long lastReplayStartMs = allSignals.stream()
                .filter(CandidateEventAssembler::isReplayBoundarySignal)
                .filter(signal -> signal.timestampMs() > searchStartMs
                        && signal.timestampMs() < boundaryReferenceMs)
                .mapToLong(CandidateSignal::timestampMs)
                .max().orElse(Long.MIN_VALUE);
        long startTimeMs;
        double startConfidence;
        String startReason;
        if (eventType == FootballEventType.GOAL) {
            GoalStartBoundary goalStart = estimateGoalStart(transcript, boundaryReferenceMs,
                    reconstructedAction, allSignals, allAnchors, current, maximumLead, searchStartMs);
            startTimeMs = goalStart.timestampMs();
            startConfidence = goalStart.confidence();
            startReason = goalStart.reason();
        } else {
            List<CandidateSignal> boundaryEvidence = allSignals.stream()
                    .filter(signal -> signal.timestampMs() >= searchStartMs
                            && signal.timestampMs() <= boundaryReferenceMs
                            && signal.confidence() >= 0.55
                            && signal.timestampMs() > lastReplayStartMs
                            && !hasInterveningGoalAnchor(signal.timestampMs(), boundaryReferenceMs,
                            allAnchors, current)
                            && isMeaningfulGoalStartSignal(signal))
                    .sorted(Comparator.comparingLong(CandidateSignal::timestampMs))
                    .toList();
            List<CandidateSignal> buildup = boundaryEvidence.stream()
                    .filter(CandidateEventAssembler::isGoalBuildupSignal)
                    .toList();
            CandidateSignal decisiveAction = boundaryEvidence.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                            || signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                            || signal.eventType() == FootballEventType.PENALTY)
                    .max(Comparator.comparingLong(CandidateSignal::timestampMs))
                    .orElseGet(() -> boundaryEvidence.stream()
                            .filter(signal -> signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE)
                            .max(Comparator.comparingLong(CandidateSignal::timestampMs))
                            .orElse(null));
            CandidateSignal connectedBuildup = findConnectedBuildup(buildup, decisiveAction,
                    maximumLead);
            CandidateSignal startCue = connectedBuildup;
            if (startCue != null) {
                startTimeMs = Math.max(0, startCue.timestampMs()
                        - Math.min(5_000, settings.preEventPaddingMs()));
                startConfidence = startCue.confidence();
                startReason = startCue.type() == CandidateSignalType.AUDIO_ENERGY_RISE
                        ? "START=ATTACK_INTENSITY_RISE; start follows the nearest local RMS energy-rise cue"
                        : "START=BUILDUP_SIGNAL; start follows the connected attack-buildup sequence";
            } else if (decisiveAction != null) {
                startTimeMs = Math.max(0, decisiveAction.timestampMs()
                        - Math.min(5_000, settings.preEventPaddingMs()));
                startConfidence = decisiveAction.confidence();
                startReason = "START=DECISIVE_ACTION; include the detected action with pre-event padding";
            } else if (reconstructedAction) {
                startTimeMs = Math.max(0,
                        boundaryReferenceMs - qualitySettings.goalFallbackPreRollMs());
                startConfidence = 0.55;
                startReason = "START=FALLBACK_PRE_ROLL; no reliable live buildup was found, so the configured "
                        + qualitySettings.goalFallbackPreRollMs() + " ms pre-roll is retained before the "
                        + "reconstructed action";
            } else {
                startTimeMs = Math.max(0, triggerTimestampMs - settings.preEventPaddingMs());
                startConfidence = 0.55;
                startReason = "Start uses configured pre-event padding";
            }
        }

        long afterglowWindowMs = eventType == FootballEventType.GOAL
                ? Math.max(qualitySettings.contextAttachWindowMs(), 20_000)
                : qualitySettings.contextAttachWindowMs();
        long eventAnchorMs = Math.max(boundaryReferenceMs, triggerTimestampMs);
        CandidateSignal replay = eventType == FootballEventType.GOAL
                ? allSignals.stream()
                .filter(CandidateEventAssembler::isReplayBoundarySignal)
                .filter(signal -> signal.timestampMs() > boundaryReferenceMs
                        && signal.timestampMs() - boundaryReferenceMs
                        <= qualitySettings.replayTemporalWindowMs())
                .min(Comparator.comparingLong(CandidateSignal::timestampMs))
                .orElse(null) : null;
        CandidateSignal restart = eventType == FootballEventType.GOAL
                ? allSignals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.RESTART_CONTEXT
                        && signal.timestampMs() > boundaryReferenceMs
                        && signal.timestampMs() - boundaryReferenceMs
                        <= Math.max(afterglowWindowMs, 30_000))
                .min(Comparator.comparingLong(CandidateSignal::timestampMs))
                .orElse(null) : null;
        long afterglowLimit = Math.min(durationMs, safeAdd(eventAnchorMs, afterglowWindowMs));
        if (replay != null) {
            afterglowLimit = Math.min(afterglowLimit, replay.timestampMs());
        }
        if (restart != null) {
            afterglowLimit = Math.min(afterglowLimit, restart.timestampMs());
        }
        long finalAfterglowLimit = afterglowLimit;
        long afterglowTimestamp = allSignals.stream()
                .filter(signal -> signal.timestampMs() >= boundaryReferenceMs
                        && signal.timestampMs() <= finalAfterglowLimit
                        && isAfterglowSignal(signal))
                .mapToLong(CandidateSignal::timestampMs)
                .max()
                .orElse(Math.min(eventAnchorMs, finalAfterglowLimit));
        long endTimeMs = restart == null
                ? Math.min(durationMs, safeAdd(afterglowTimestamp, settings.postEventPaddingMs()))
                : Math.min(durationMs, restart.timestampMs());
        String endReason = restart != null
                ? "END=IMMEDIATE_RESTART; stop at the first detected restart after the live event"
                : replay != null && replay.timestampMs() <= afterglowTimestamp
                ? "END=REPLAY_BOUNDARY; stop before replay material can extend the live clip"
                : afterglowTimestamp > boundaryReferenceMs
                ? "END=GOAL_REACTION; include the latest immediate reaction and configured post-event padding"
                : "END=FALLBACK_POST_WINDOW; no immediate reaction cue was found";
        if (replay != null && replay.timestampMs() <= endTimeMs) {
            endTimeMs = Math.min(endTimeMs, replay.timestampMs());
            endReason = "END=REPLAY_BOUNDARY; stop before replay material can extend the live clip";
        }
        if (endTimeMs <= startTimeMs) {
            endTimeMs = Math.min(durationMs,
                    safeAdd(Math.max(startTimeMs, boundaryReferenceMs),
                            Math.max(1, settings.postEventPaddingMs())));
            startTimeMs = Math.min(startTimeMs, Math.max(0, boundaryReferenceMs - 1));
        }

        if (startTimeMs > triggerTimestampMs) {
            startTimeMs = triggerTimestampMs;
            startReason += "; WINDOW=EXTENDED_TO_INCLUDE_TRIGGER; reconstructed action follows the detection trigger";
        }
        if (endTimeMs < triggerTimestampMs) {
            endTimeMs = triggerTimestampMs;
            endReason += "; WINDOW=EXTENDED_TO_INCLUDE_TRIGGER; detection trigger follows the reconstructed boundary";
        }

        long maximumDurationMs = qualitySettings.maximumCandidateDurationMs();
        if (endTimeMs - startTimeMs > maximumDurationMs) {
            long cappedStart = Math.max(0, endTimeMs - maximumDurationMs);
            if (cappedStart <= triggerTimestampMs) {
                startTimeMs = cappedStart;
                startReason += "; START=MAXIMUM_DURATION_TRIM; older context trimmed to preserve action and reaction";
            } else {
                startTimeMs = triggerTimestampMs;
                endTimeMs = Math.min(endTimeMs, safeAdd(startTimeMs, maximumDurationMs));
                startReason += "; START=MAXIMUM_DURATION_TRIM; trigger prioritized within the configured maximum";
            }
        }
        if (durationMs > 0) {
            startTimeMs = Math.max(0, Math.min(startTimeMs, durationMs - 1));
            endTimeMs = Math.max(startTimeMs + 1, Math.min(endTimeMs, durationMs));
        } else {
            startTimeMs = 0;
            endTimeMs = 0;
        }
        double endConfidence = afterglowTimestamp > triggerTimestampMs ? 0.78 : 0.55;
        if (restart != null) {
            endReason += "; POST_RESTART";
        }
        if (replay != null && replay.timestampMs() <= endTimeMs) {
            endReason += "; REPLAY_CONTEXT";
        }
        return new Boundary(startTimeMs, endTimeMs, startConfidence, endConfidence,
                startReason, endReason);
    }

    private GoalStartBoundary estimateGoalStart(Transcript transcript, long boundaryReferenceMs,
                                                 boolean reconstructedAction,
                                                 List<CandidateSignal> allSignals,
                                                 List<AnchorCluster> allAnchors,
                                                 AnchorCluster current, long maximumLead,
                                                 long searchStartMs) {
        long lastSequenceBoundaryMs = allSignals.stream()
                .filter(CandidateEventAssembler::isGoalStartFenceSignal)
                .filter(signal -> signal.timestampMs() >= searchStartMs
                        && signal.timestampMs() < boundaryReferenceMs)
                .mapToLong(CandidateSignal::timestampMs)
                .max().orElse(Long.MIN_VALUE);
        long sequenceStartMs = Math.max(searchStartMs, lastSequenceBoundaryMs);
        List<CandidateSignal> evidence = allSignals.stream()
                .filter(signal -> signal.timestampMs() >= sequenceStartMs
                        && signal.timestampMs() <= boundaryReferenceMs
                        && signal.confidence() >= 0.55
                        && !hasInterveningGoalAnchor(signal.timestampMs(), boundaryReferenceMs,
                        allAnchors, current))
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs))
                .toList();
        CandidateSignal penaltySetup = findPenaltySetup(transcript, evidence,
                sequenceStartMs, boundaryReferenceMs);
        List<CandidateSignal> semanticBuildup = evidence.stream()
                .filter(CandidateEventAssembler::isSemanticGoalBuildupSignal)
                .toList();
        CandidateSignal decisiveAction = evidence.stream()
                .filter(CandidateEventAssembler::isDecisiveGoalAction)
                .max(Comparator.comparingLong(CandidateSignal::timestampMs))
                .orElse(null);
        CandidateSignal connectedBuildup = findConnectedAttack(semanticBuildup,
                decisiveAction, boundaryReferenceMs, maximumLead,
                qualitySettings.contextAttachWindowMs(), transcript);
        CandidateSignal audioBuildup = findAudioBuildup(evidence, boundaryReferenceMs,
                qualitySettings.contextAttachWindowMs());

        long startPaddingMs = Math.min(5_000, settings.preEventPaddingMs());
        if (penaltySetup != null) {
            return new GoalStartBoundary(Math.max(0, penaltySetup.timestampMs() - startPaddingMs),
                    penaltySetup.confidence(),
                    "START=PENALTY_SETUP; start precedes transcript or event evidence of the penalty setup");
        }
        if (connectedBuildup != null) {
            return new GoalStartBoundary(Math.max(0, connectedBuildup.timestampMs() - startPaddingMs),
                    connectedBuildup.confidence(),
                    "START=CONNECTED_ATTACK; start follows a temporally connected attack-buildup sequence");
        }
        if (audioBuildup != null) {
            boolean energyRiseOnly = audioBuildup.type() == CandidateSignalType.AUDIO_ENERGY_RISE
                    && !audioBuildup.evidence().startsWith("Independent audio buildup features");
            return new GoalStartBoundary(Math.max(0, audioBuildup.timestampMs() - startPaddingMs),
                    audioBuildup.confidence(),
                    energyRiseOnly
                            ? "START=ATTACK_INTENSITY_RISE; start follows the nearest local RMS energy-rise cue"
                            : "START=AUDIO_BUILDUP; start follows a cluster of independent pre-event audio transitions");
        }
        if (decisiveAction != null) {
            return new GoalStartBoundary(Math.max(0, decisiveAction.timestampMs() - startPaddingMs),
                    decisiveAction.confidence(),
                    "START=DECISIVE_ACTION; include the detected shot or penalty action with pre-event padding");
        }
        return new GoalStartBoundary(
                Math.max(0, boundaryReferenceMs - qualitySettings.goalFallbackPreRollMs()),
                0.55,
                "START=FALLBACK_PRE_ROLL; no reliable connected live buildup was found, so the configured "
                        + qualitySettings.goalFallbackPreRollMs() + " ms pre-roll is retained"
                        + (reconstructedAction ? " before the reconstructed action" : ""));
    }

    private CandidateSignal findPenaltySetup(Transcript transcript, List<CandidateSignal> evidence,
                                             long sequenceStartMs, long boundaryReferenceMs) {
        String localText = TranscriptTextNormalizer.normalize(transcriptContext(transcript,
                sequenceStartMs, boundaryReferenceMs));
        CandidateSignal typedSignal = evidence.stream()
                .filter(signal -> signal.eventType() == FootballEventType.PENALTY
                        && (signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                        || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                        || signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD))
                .filter(signal -> localText != null && containsPenaltySetupPhrase(localText))
                .min(Comparator.comparingLong(CandidateSignal::timestampMs))
                .orElse(null);
        if (typedSignal != null) {
            return typedSignal;
        }
        return transcript.getSegments().stream()
                .filter(segment -> segment.startTimeMs() >= sequenceStartMs
                        && segment.startTimeMs() < boundaryReferenceMs)
                .filter(segment -> containsExplicitPenaltySetupPhrase(
                        TranscriptTextNormalizer.normalize(segment.text())))
                .min(Comparator.comparingLong(TranscriptSegment::startTimeMs))
                .map(segment -> new CandidateSignal(CandidateSignalType.TRANSCRIPT_PENALTY,
                        FootballEventType.PENALTY, 0.64, segment.startTimeMs(),
                        "Explicit transcript wording identifies the penalty attempt or setup"))
                .orElse(null);
    }

    private static boolean containsPenaltySetupPhrase(String text) {
        return text != null && List.of("penalty", "penalti", "penalte", "penalty spot", "penalty kick",
                                "tir au but", "punto de penalti", "point de penalty", "penalty pour")
                .stream().anyMatch(text::contains);
    }

    private static boolean containsExplicitPenaltySetupPhrase(String text) {
        return List.of("penalty awarded", "penalty kick", "takes the penalty",
                        "taking the penalty", "the penalty is taken", "lanzamiento de penalti",
                        "lanzamiento de penante", "lanza el penalti", "penalti señalado",
                        "penalty spot", "point de penalty", "frappe le penalty")
                .stream().anyMatch(text::contains);
    }

    private static CandidateSignal findConnectedAttack(List<CandidateSignal> buildup,
                                                        CandidateSignal decisiveAction,
                                                        long boundaryReferenceMs, long maximumLeadMs,
                                                        long contextAttachWindowMs,
                                                        Transcript transcript) {
        if (buildup.isEmpty()) {
            return null;
        }
        if (decisiveAction != null
                && boundaryReferenceMs - decisiveAction.timestampMs() > maximumLeadMs) {
            return null;
        }
        long maximumCueGapMs = Math.max(contextAttachWindowMs * 4, 20_000);
        long anchorTimestampMs;
        CandidateSignal earliestConnected;
        int connectedCueCount;
        if (decisiveAction == null) {
            CandidateSignal latestCue = buildup.getLast();
            if (boundaryReferenceMs - latestCue.timestampMs() > maximumLeadMs) {
                return null;
            }
            anchorTimestampMs = latestCue.timestampMs();
            earliestConnected = latestCue;
            connectedCueCount = 1;
        } else {
            anchorTimestampMs = decisiveAction.timestampMs();
            earliestConnected = null;
            connectedCueCount = 0;
        }
        for (int index = buildup.size() - 1; index >= 0; index--) {
            CandidateSignal cue = buildup.get(index);
            if (cue.timestampMs() >= anchorTimestampMs) {
                continue;
            }
            if (anchorTimestampMs - cue.timestampMs() > maximumCueGapMs
                    || !transcriptSupportsContinuity(transcript, cue.timestampMs(),
                    anchorTimestampMs, contextAttachWindowMs)) {
                break;
            }
            earliestConnected = cue;
            connectedCueCount++;
            anchorTimestampMs = cue.timestampMs();
        }
        if (earliestConnected == null) {
            return null;
        }
        if (decisiveAction != null
                || connectedCueCount > 1
                || boundaryReferenceMs - earliestConnected.timestampMs() <= contextAttachWindowMs
                || earliestConnected.type() == CandidateSignalType.ATTACK_BUILDUP
                && boundaryReferenceMs - earliestConnected.timestampMs() <= maximumLeadMs) {
            return earliestConnected;
        }
        return null;
    }

    private static boolean transcriptSupportsContinuity(Transcript transcript, long startTimeMs,
                                                        long endTimeMs, long maximumGapMs) {
        long coveredUntilMs = startTimeMs;
        List<TranscriptSegment> segments = transcript.getSegments().stream()
                .filter(segment -> segment.endTimeMs() >= startTimeMs
                        && segment.startTimeMs() <= endTimeMs)
                .sorted(Comparator.comparingLong(TranscriptSegment::startTimeMs)
                        .thenComparingInt(TranscriptSegment::sequence))
                .toList();
        for (TranscriptSegment segment : segments) {
            if (segment.startTimeMs() > coveredUntilMs + maximumGapMs) {
                return false;
            }
            coveredUntilMs = Math.max(coveredUntilMs, segment.endTimeMs());
            if (coveredUntilMs >= endTimeMs) {
                return true;
            }
        }
        return endTimeMs - coveredUntilMs <= maximumGapMs;
    }

    private static CandidateSignal findAudioBuildup(List<CandidateSignal> evidence,
                                                     long boundaryReferenceMs,
                                                     long contextAttachWindowMs) {
        long clusterGapMs = Math.max(1, contextAttachWindowMs / 2);
        long maximumTailMs = clusterGapMs * 6;
        List<CandidateSignal> cues = evidence.stream()
                .filter(signal -> isIndependentAudioBuildupSignal(signal.type())
                        && signal.confidence() >= 0.60
                        && signal.timestampMs() < boundaryReferenceMs
                        && boundaryReferenceMs - signal.timestampMs() <= contextAttachWindowMs * 6)
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs))
                .toList();
        List<CandidateSignal> bestCluster = List.of();
        int first = 0;
        while (first < cues.size()) {
            int last = first;
            while (last + 1 < cues.size()
                    && cues.get(last + 1).timestampMs() - cues.get(last).timestampMs() <= clusterGapMs) {
                last++;
            }
            List<CandidateSignal> cluster = cues.subList(first, last + 1);
            long latestTimestampMs = cluster.getLast().timestampMs();
            long independentFamilies = cluster.stream()
                    .map(signal -> audioBuildupFamily(signal.type()))
                    .distinct()
                    .count();
            if (independentFamilies >= 2
                    && boundaryReferenceMs - latestTimestampMs <= maximumTailMs
                    && (bestCluster.isEmpty()
                    || latestTimestampMs > bestCluster.getLast().timestampMs())) {
                bestCluster = List.copyOf(cluster);
            }
            first = last + 1;
        }
        if (bestCluster.isEmpty()) {
            return evidence.stream()
                    .filter(signal -> signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE
                            && signal.confidence() >= 0.65
                            && signal.timestampMs() < boundaryReferenceMs
                            && boundaryReferenceMs - signal.timestampMs() <= contextAttachWindowMs * 6)
                    .max(Comparator.comparingLong(CandidateSignal::timestampMs))
                    .orElse(null);
        }
        List<CandidateSignal> selectedCluster = bestCluster;
        CandidateSignal firstCue = selectedCluster.getFirst();
        return new CandidateSignal(firstCue.type(), firstCue.eventType(),
                selectedCluster.stream().mapToDouble(CandidateSignal::confidence).max().orElse(0),
                firstCue.timestampMs(),
                "Independent audio buildup features remain connected through "
                        + selectedCluster.getLast().timestampMs() + " ms");
    }

    private static boolean isIndependentAudioBuildupSignal(CandidateSignalType type) {
        return audioBuildupFamily(type) != null;
    }

    private static String audioBuildupFamily(CandidateSignalType type) {
        return switch (type) {
            case AUDIO_ENERGY_RISE, AUDIO_SPIKE, AUDIO_SUSTAINED -> "ENERGY";
            case SPEECH_RATE_SPIKE -> "SPEECH_RATE";
            case PITCH_RISE -> "PITCH";
            default -> null;
        };
    }

    private static boolean isDecisiveGoalAction(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                && signal.eventType() == FootballEventType.SHOT
                || signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                && signal.eventType() == FootballEventType.PENALTY
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                && (signal.eventType() == FootballEventType.SHOT
                || signal.eventType() == FootballEventType.PENALTY);
    }

    private static boolean isSemanticGoalBuildupSignal(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.ATTACK_BUILDUP
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                && (signal.eventType() == FootballEventType.ATTACK
                || signal.eventType() == FootballEventType.COUNTER_ATTACK
                || signal.eventType() == FootballEventType.BIG_CHANCE);
    }

    private static boolean isGoalStartFenceSignal(CandidateSignal signal) {
        return isReplayBoundarySignal(signal)
                || signal.type() == CandidateSignalType.INJURY_CONTEXT
                || signal.type() == CandidateSignalType.RESTART_CONTEXT
                || signal.type() == CandidateSignalType.UNRELATED_ACTION_CONTEXT
                || signal.type() == CandidateSignalType.GOAL_KICK_CONTEXT;
    }

    private static CandidateSignal findConnectedBuildup(List<CandidateSignal> buildup,
                                                        CandidateSignal decisiveAction,
                                                        long maximumCueGapMs) {
        if (buildup.isEmpty()) {
            return null;
        }
        CandidateSignal anchor = decisiveAction == null ? buildup.getLast() : decisiveAction;
        CandidateSignal earliestConnected = null;
        for (int index = buildup.size() - 1; index >= 0; index--) {
            CandidateSignal cue = buildup.get(index);
            if (anchor.timestampMs() - cue.timestampMs() > maximumCueGapMs) {
                break;
            }
            earliestConnected = cue;
            anchor = cue;
        }
        return earliestConnected;
    }

    private static boolean isMeaningfulGoalStartSignal(CandidateSignal signal) {
        return isGoalBuildupSignal(signal)
                || signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                && signal.eventType() == FootballEventType.PENALTY
                || signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                && signal.eventType() == FootballEventType.PENALTY;
    }

    private static boolean isGoalBuildupSignal(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.ATTACK_BUILDUP
                || signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE
                || signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                && signal.eventType() == FootballEventType.SHOT
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                && (signal.eventType() == FootballEventType.ATTACK
                || signal.eventType() == FootballEventType.COUNTER_ATTACK
                || signal.eventType() == FootballEventType.BIG_CHANCE);
    }

    private static boolean isReplayBoundarySignal(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.REPLAY_CONTEXT
                || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                || signal.type() == CandidateSignalType.POST_EVENT_COMMENTARY;
    }

    private static boolean hasInterveningGoalAnchor(long startTimeMs, long endTimeMs,
                                                      List<AnchorCluster> anchors, AnchorCluster current) {
        long start = Math.min(startTimeMs, endTimeMs);
        long end = Math.max(startTimeMs, endTimeMs);
        return anchors.stream()
                .filter(anchor -> anchor != current && anchor.eventType == FootballEventType.GOAL)
                .anyMatch(anchor -> anchor.firstTimestampMs > start && anchor.firstTimestampMs < end);
    }

    private static boolean isAfterglowSignal(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.AUDIO_SPIKE
                || signal.type() == CandidateSignalType.AUDIO_SUSTAINED
                || signal.type() == CandidateSignalType.HIGH_EXCITEMENT
                || signal.type() == CandidateSignalType.VOICE_EXCITEMENT
                || signal.type() == CandidateSignalType.PITCH_RISE
                || signal.type() == CandidateSignalType.PITCH_VARIANCE
                || signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                || signal.type() == CandidateSignalType.CROWD_REACTION_PROXY
                || signal.type() == CandidateSignalType.TRANSCRIPT_EMPHASIS
                || signal.type() == CandidateSignalType.TRANSCRIPT_REPETITION
                || signal.eventType() == FootballEventType.CELEBRATION;
    }

    private double directEventConfidence(List<CandidateSignal> signals, FootballEventType eventType) {
        return signals.stream()
                .filter(signal -> isEventSpecificSignal(signal.type(), eventType)
                        || eventType == FootballEventType.GOAL
                        && isRetrospectiveGoalConfirmation(signal))
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
    }

    private boolean hasExplicitScoringPhrase(List<CandidateSignal> signals) {
        return signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.GOAL
                        && signal.confidence() >= settings.singleSignalConfidenceThreshold()
                        && signal.evidence().startsWith("Football phrase match: "))
                || signals.stream().anyMatch(CandidateEventAssembler::isRetrospectiveGoalConfirmation);
    }

    private static boolean hasInferredGoalHypothesis(List<CandidateSignal> signals) {
        return signals.stream().anyMatch(signal ->
                signal.eventType() == FootballEventType.GOAL
                        && (signal.type() == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS
                        || signal.type() == CandidateSignalType.TRANSCRIPT_GOAL
                        && signal.evidence().startsWith("Inferred goal hypothesis from a shot")));
    }

    private static boolean hasRetrospectiveGoalConfirmation(List<CandidateSignal> signals) {
        return signals.stream().anyMatch(CandidateEventAssembler::isRetrospectiveGoalConfirmation);
    }

    private boolean hasPenaltyAttemptCorroboration(Transcript transcript, long triggerTimestampMs,
                                                   List<CandidateSignal> signals,
                                                   double reactionConfidence) {
        long contextStartMs = Math.max(0, triggerTimestampMs - 20_000);
        long contextEndMs = safeAdd(triggerTimestampMs, 20_000);
        String context = TranscriptTextNormalizer.normalize(
                transcriptContext(transcript, contextStartMs, contextEndMs));
        boolean penaltyMentioned = List.of("penalty", "penalti", "penalte", "penalty kick",
                        "tir au but", "penalty spot", "punto de penalti")
                .stream().anyMatch(context::contains);
        boolean nearbyShot = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 10_000);
        boolean missedOutcome = signals.stream().anyMatch(signal ->
                (signal.eventType() == FootballEventType.SAVE
                        || signal.eventType() == FootballEventType.MISSED_PENALTY)
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 20_000);
        return penaltyMentioned && nearbyShot && reactionConfidence >= qualitySettings.minimumGoalReactionConfidence()
                && !missedOutcome && !context.contains("penalty miss")
                && !context.contains("missed the penalty") && !context.contains("rate le penalty")
                && !context.contains("gardien arrete le penalty");
    }

    private boolean hasOrderedLiveEvidence(List<CandidateSignal> signals, long triggerTimestampMs,
                                           double reactionConfidence, double textualReactionConfidence) {
        if (reactionConfidence < qualitySettings.minimumGoalReactionConfidence()
                || textualReactionConfidence < 0.68) {
            return false;
        }
        long maximumLead = Math.min(qualitySettings.attackBuildupMaximumLeadMs(),
                qualitySettings.boundarySearchBackMs());
        boolean buildup = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.ATTACK_BUILDUP
                        && signal.timestampMs() < triggerTimestampMs
                        && triggerTimestampMs - signal.timestampMs() <= maximumLead);
        boolean shot = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 20_000);
        return buildup && shot;
    }

    private boolean hasNearbyRetrospectiveReference(List<CandidateSignal> signals,
                                                    long triggerTimestampMs) {
        return nearbyRetrospectiveConfidence(signals, triggerTimestampMs,
                qualitySettings.maximumCandidateDurationMs() / 2) >= 0.68;
    }

    private static double nearbyRetrospectiveConfidence(List<CandidateSignal> signals,
                                                        long triggerTimestampMs,
                                                        long maximumDistanceMs) {
        return signals.stream()
                .filter(signal ->
                signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= maximumDistanceMs
                        && signal.confidence() >= 0.68)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
    }

    private boolean hasSetPieceShotWithoutScoring(Transcript transcript, long triggerTimestampMs,
                                                  List<CandidateSignal> signals) {
        String context = TranscriptTextNormalizer.normalize(transcriptContext(transcript,
                Math.max(0, triggerTimestampMs - qualitySettings.maximumCandidateDurationMs() / 2),
                safeAdd(triggerTimestampMs, qualitySettings.maximumCandidateDurationMs() / 2)));
        boolean setPieceMentioned = List.of("corner", "corners", "coup de corner", "corner kick",
                "saque de esquina", "tiro de esquina", "corner flag")
                .stream().anyMatch(context::contains);
        boolean nearbyShot = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 20_000);
        return setPieceMentioned && nearbyShot;
    }

    private static boolean hasShotFollowedByContinuedPlay(List<CandidateSignal> signals,
                                                          long triggerTimestampMs) {
        boolean shot = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                        && Math.abs(signal.timestampMs() - triggerTimestampMs) <= 10_000);
        boolean attackContinues = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                        && signal.eventType() == FootballEventType.ATTACK
                        && signal.timestampMs() > triggerTimestampMs
                        && signal.timestampMs() - triggerTimestampMs <= 15_000);
        return shot && attackContinues;
    }

    private static double audioConfidence(List<CandidateSignal> signals) {
        double voicedExcitement = signals.stream()
                .filter(signal -> isAudioSignal(signal.type()))
                .filter(signal -> signal.type() == CandidateSignalType.HIGH_EXCITEMENT
                        || signal.type() == CandidateSignalType.VOICE_EXCITEMENT
                        || signal.type() == CandidateSignalType.PITCH_RISE
                        || signal.type() == CandidateSignalType.PITCH_VARIANCE)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
        double nonspecificIntensity = signals.stream()
                .filter(signal -> isAudioSignal(signal.type()))
                .filter(signal -> signal.type() == CandidateSignalType.AUDIO_SPIKE
                        || signal.type() == CandidateSignalType.AUDIO_SUSTAINED
                        || signal.type() == CandidateSignalType.CROWD_REACTION_PROXY)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0) * 0.60;
        return Math.max(voicedExcitement, nonspecificIntensity);
    }

    private List<CandidateScoreComponent> scoreContributions(List<CandidateSignal> signals,
                                                             FootballEventType eventType,
                                                             double eventSpecificConfidence,
                                                             double audioReactionConfidence,
                                                             double buildupConfidence,
                                                             double replayConfidence,
                                                             double goalNegativeContextConfidence,
                                                             double finalScore,
                                                             double totalWeight) {
        List<CandidateScoreComponent> components = new ArrayList<>();
        addScoreComponent(components, CandidateScoreComponentType.EVENT_SPECIFIC_EVIDENCE,
                eventSpecificConfidence, settings.transcriptWeight() / totalWeight,
                eventSpecificConfidence * settings.transcriptWeight() / totalWeight,
                signals.stream().filter(signal -> isEventSpecificSignal(signal.type(), eventType)
                                || eventType == FootballEventType.GOAL
                                && isRetrospectiveGoalConfirmation(signal))
                        .map(CandidateSignal::type).distinct().toList(),
                "Event-specific evidence contribution: confidence multiplied by the configured event-specific "
                        + "(transcript-weighted) share.");
        addScoreComponent(components, CandidateScoreComponentType.AUDIO_REACTION,
                audioReactionConfidence, settings.audioWeight() / totalWeight,
                audioReactionConfidence * settings.audioWeight() / totalWeight,
                signals.stream().filter(signal -> isAudioSignal(signal.type()))
                        .map(CandidateSignal::type).distinct().toList(),
                "Audio contribution uses voiced excitement at full confidence; non-specific intensity/crowd "
                        + "signals are attenuated to 60% confidence and cannot identify an event by themselves.");
        boolean agreement = eventSpecificConfidence > 0 && audioReactionConfidence > 0;
        addScoreComponent(components, CandidateScoreComponentType.SIGNAL_AGREEMENT,
                agreement ? 1 : 0, settings.signalAgreementBonus() * 0.25,
                agreement ? settings.signalAgreementBonus() * 0.25 : 0,
                agreement ? signals.stream().filter(signal ->
                                isEventSpecificSignal(signal.type(), eventType) || isAudioSignal(signal.type()))
                        .map(CandidateSignal::type).distinct().toList() : List.of(),
                "Configured cross-family agreement bonus; applied only when event-specific and audio evidence "
                        + "are both present.");
        addScoreComponent(components, CandidateScoreComponentType.ATTACK_BUILDUP,
                buildupConfidence, 0.05, 0.05 * buildupConfidence,
                signals.stream().filter(signal -> signal.type() == CandidateSignalType.ATTACK_BUILDUP
                                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                                && (signal.eventType() == FootballEventType.ATTACK
                                || signal.eventType() == FootballEventType.COUNTER_ATTACK)
                                || signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE)
                        .map(CandidateSignal::type).distinct().toList(),
                "Connected buildup contribution: 5% of the measured buildup confidence.");
        double replayWeight = -qualitySettings.replayPenaltyWeight() * 0.5;
        addScoreComponent(components, CandidateScoreComponentType.REPLAY_PENALTY,
                replayConfidence, replayWeight, replayWeight * replayConfidence,
                signals.stream().filter(signal -> signal.type() == CandidateSignalType.REPLAY_CONTEXT
                                || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT)
                        .map(CandidateSignal::type).distinct().toList(),
                "Replay penalty is applied only after the existing same-event replay association succeeds.");
        double negativeContextWeight = eventType == FootballEventType.GOAL
                ? -qualitySettings.goalNegativeContextPenaltyWeight() : 0;
        addScoreComponent(components, CandidateScoreComponentType.CONTEXTUAL_NEGATIVE_EVIDENCE,
                goalNegativeContextConfidence, negativeContextWeight,
                negativeContextWeight * goalNegativeContextConfidence,
                signals.stream().filter(CandidateEventAssembler::isGoalNegativeContext)
                        .map(CandidateSignal::type).distinct().toList(),
                "Goal negation, correction, historical, retrospective, or replay context suppresses live-goal "
                        + "confidence independently of the lexical phrase strength.");
        double scoredBeforeClamp = components.stream().mapToDouble(CandidateScoreComponent::contribution).sum();
        addScoreComponent(components, CandidateScoreComponentType.SCORE_CLAMP_ADJUSTMENT,
                0, 0, finalScore - scoredBeforeClamp, List.of(),
                "Residual adjustment records the configured [0, 0.98] score clamp.");
        return List.copyOf(components);
    }

    private static void addScoreComponent(List<CandidateScoreComponent> components,
                                          CandidateScoreComponentType type,
                                          double confidence, double weight, double contribution,
                                          List<CandidateSignalType> supportingTypes,
                                          String explanation) {
        components.add(new CandidateScoreComponent(type, confidence, weight, contribution,
                supportingTypes, explanation));
    }

    private static double textualReactionConfidence(List<CandidateSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_EMPHASIS
                        || signal.type() == CandidateSignalType.TRANSCRIPT_REPETITION)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
    }

    private static double buildupConfidence(List<CandidateSignal> signals) {
        double transcriptBuildup = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.ATTACK_BUILDUP
                        || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                        && (signal.eventType() == FootballEventType.ATTACK
                        || signal.eventType() == FootballEventType.COUNTER_ATTACK))
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
        double audioRise = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
        return Math.max(transcriptBuildup, audioRise * 0.25);
    }

    private static double replayProbability(List<CandidateSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.REPLAY_CONTEXT
                        || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT)
                .mapToDouble(CandidateSignal::confidence)
                .max().orElse(0);
    }

    private static boolean hasInjuryThenReplay(List<CandidateSignal> signals, long triggerTimestampMs) {
        boolean replay = signals.stream().anyMatch(signal ->
                (signal.type() == CandidateSignalType.REPLAY_CONTEXT
                        || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT)
                        && signal.timestampMs() <= triggerTimestampMs
                        && signal.confidence() >= 0.68);
        boolean injury = signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.INJURY_CONTEXT
                        && signal.timestampMs() <= triggerTimestampMs
                        && triggerTimestampMs - signal.timestampMs() <= 90_000);
        return replay && injury;
    }

    private static long chooseTrigger(List<CandidateSignal> signals) {
        Optional<Long> reconstructedLiveAction = signals.stream()
                .filter(CandidateEventAssembler::isRetrospectiveGoalConfirmation)
                .map(CandidateSignal::timestampMs)
                .findFirst();
        if (reconstructedLiveAction.isPresent()) {
            return reconstructedLiveAction.get();
        }
        return signals.stream()
                .filter(signal -> isEventSpecificSignal(signal.type(), signal.eventType()))
                .max(Comparator.comparingDouble(CandidateSignal::confidence)
                        .thenComparingLong(signal -> -signal.timestampMs()))
                .map(CandidateSignal::timestampMs)
                .orElse(-1L);
    }

    private static boolean isRetrospectiveGoalConfirmation(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                && signal.eventType() == FootballEventType.GOAL
                && signal.evidence().startsWith("RETROSPECTIVE_GOAL_CONFIRMATION:");
    }

    private static boolean isEventSpecificSignal(CandidateSignalType signalType,
                                                 FootballEventType eventType) {
        if (eventType == null || !isClassifiableEventType(eventType)) {
            return false;
        }
        return switch (eventType) {
            case GOAL -> signalType == CandidateSignalType.TRANSCRIPT_GOAL
                    || signalType == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS
                    || signalType == CandidateSignalType.OWN_GOAL;
            case SHOT, NEAR_MISS -> signalType == CandidateSignalType.TRANSCRIPT_SHOT;
            case PENALTY, MISSED_PENALTY, PENALTY_MISSED ->
                    signalType == CandidateSignalType.TRANSCRIPT_PENALTY;
            case YELLOW_CARD, RED_CARD -> signalType == CandidateSignalType.TRANSCRIPT_CARD;
            default -> signalType == CandidateSignalType.TRANSCRIPT_EVENT;
        };
    }

    private static boolean isGoalNegativeContext(CandidateSignal signal) {
        return signal.type() == CandidateSignalType.NEGATION_CONTEXT
                || signal.type() == CandidateSignalType.CORRECTION_CONTEXT
                || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                || signal.type() == CandidateSignalType.HISTORICAL_REFERENCE
                || signal.type() == CandidateSignalType.REPLAY_CONTEXT;
    }

    private static boolean isClassifiableEventType(FootballEventType eventType) {
        return switch (eventType) {
            case UNKNOWN, COMMENTATOR_REACTION, CROWD_REACTION, CELEBRATION, DRAMATIC_MOMENT -> false;
            default -> true;
        };
    }

    private static boolean isTranscriptSignal(CandidateSignalType type) {
        return type == CandidateSignalType.TRANSCRIPT_KEYWORD
                || type == CandidateSignalType.TRANSCRIPT_GOAL
                || type == CandidateSignalType.TRANSCRIPT_SHOT
                || type == CandidateSignalType.TRANSCRIPT_PENALTY
                || type == CandidateSignalType.TRANSCRIPT_CARD
                || type == CandidateSignalType.TRANSCRIPT_EVENT;
    }

    private static boolean isReactionSignal(CandidateSignalType type) {
        return type == CandidateSignalType.TRANSCRIPT_EMPHASIS
                || type == CandidateSignalType.TRANSCRIPT_REPETITION
                || type == CandidateSignalType.SPEECH_RATE_SPIKE
                || isAudioSignal(type);
    }

    private static boolean isAudioSignal(CandidateSignalType type) {
        return type == CandidateSignalType.AUDIO_SPIKE
                || type == CandidateSignalType.AUDIO_SUSTAINED
                || type == CandidateSignalType.HIGH_EXCITEMENT
                || type == CandidateSignalType.VOICE_EXCITEMENT
                || type == CandidateSignalType.PITCH_RISE
                || type == CandidateSignalType.PITCH_VARIANCE
                || type == CandidateSignalType.CROWD_REACTION_PROXY;
    }

    private static String transcriptContext(Transcript transcript, long startTimeMs, long endTimeMs) {
        StringBuilder context = new StringBuilder();
        transcript.getSegments().stream()
                .filter(segment -> segment.startTimeMs() <= endTimeMs && segment.endTimeMs() >= startTimeMs)
                .sorted(Comparator.comparingLong(TranscriptSegment::startTimeMs)
                        .thenComparingInt(TranscriptSegment::sequence))
                .forEach(segment -> {
                    if (context.length() < MAXIMUM_CONTEXT_LENGTH) {
                        if (!context.isEmpty()) {
                            context.append(' ');
                        }
                        context.append(segment.text());
                    }
                });
        return context.isEmpty() ? null : context.substring(0, Math.min(context.length(), MAXIMUM_CONTEXT_LENGTH));
    }

    private static double contextSimilarity(String first, String second) {
        if (first == null || second == null || first.isBlank() || second.isBlank()) {
            return 0;
        }
        Set<String> firstTokens = new HashSet<>(Arrays.asList(
                TranscriptTextNormalizer.normalize(first).split("\\s+")));
        Set<String> secondTokens = new HashSet<>(Arrays.asList(
                TranscriptTextNormalizer.normalize(second).split("\\s+")));
        Set<String> union = new HashSet<>(firstTokens);
        union.addAll(secondTokens);
        Set<String> intersection = firstTokens.stream().filter(secondTokens::contains).collect(Collectors.toSet());
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record AnchorKey(FootballEventType eventType, long timestampMs) {
    }

    private static final class AnchorCluster {
        private final FootballEventType eventType;
        private final long firstTimestampMs;
        private long lastTimestampMs;
        private final List<CandidateSignal> signals;

        private AnchorCluster(FootballEventType eventType, long timestampMs,
                              List<CandidateSignal> signals) {
            this.eventType = eventType;
            this.firstTimestampMs = timestampMs;
            this.lastTimestampMs = timestampMs;
            this.signals = signals;
        }
    }

    private record Boundary(long startTimeMs, long endTimeMs, double startConfidence,
                            double endConfidence, String startReason, String endReason) {
    }

    private record GoalStartBoundary(long timestampMs, double confidence, String reason) {
    }

    private record AssociatedFoul(CandidateSignal signal, double contextSimilarity) {
    }
}
