package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record TemporalEventSequence(String assessment, List<TemporalAtomicEvent> events,
                                    List<TemporalEventRelationship> relationships,
                                    List<String> negativeEvidence) {
    private static final Pattern ASSOCIATED_REPLAY_TIMESTAMP =
            Pattern.compile("replay-only GOAL candidate [0-9a-f-]+ at (\\d+) ms was associated");
    private static final Pattern REPLAY_CUE_TIMESTAMP = Pattern.compile("replay cue at (\\d+) ms");

    public TemporalEventSequence {
        events = List.copyOf(events);
        relationships = List.copyOf(relationships);
        negativeEvidence = List.copyOf(negativeEvidence);
    }

    public static TemporalEventSequence from(CandidateEvent candidate) {
        return from(candidate, CandidateDetectionQualitySettings.defaults());
    }

    public static TemporalEventSequence from(CandidateEvent candidate,
                                             CandidateDetectionQualitySettings settings) {
        List<CandidateSignal> ordered = candidate.signals().stream()
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs)
                        .thenComparing(signal -> signal.type().name())
                        .thenComparing(CandidateSignal::evidence))
                .toList();
        Map<AtomKey, List<CandidateSignal>> grouped = new LinkedHashMap<>();
        for (CandidateSignal signal : ordered) {
            TemporalAtomicEventType type = atomicType(signal, candidate);
            if (type != null) {
                long atomTimestampMs = type == TemporalAtomicEventType.GOAL
                        && candidate.eventType() == FootballEventType.GOAL
                        ? candidate.triggerTimestampMs()
                        : type == TemporalAtomicEventType.REPLAY_EVENT
                        ? associatedReplayTimestamp(signal.evidence()).orElse(signal.timestampMs())
                        : signal.timestampMs();
                grouped.computeIfAbsent(new AtomKey(atomTimestampMs, type), ignored -> new ArrayList<>())
                        .add(signal);
            }
        }
        if (candidate.status() == CandidateEventStatus.DETECTED
                && candidate.eventType() == FootballEventType.GOAL) {
            grouped.computeIfAbsent(new AtomKey(candidate.triggerTimestampMs(), TemporalAtomicEventType.GOAL),
                    ignored -> new ArrayList<>());
        }
        List<TemporalAtomicEvent> events = new ArrayList<>();
        List<Map.Entry<AtomKey, List<CandidateSignal>>> orderedAtoms = grouped.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<AtomKey, List<CandidateSignal>> entry) ->
                                entry.getKey().timestampMs())
                        .thenComparing(entry -> entry.getKey().type().name()))
                .toList();
        for (Map.Entry<AtomKey, List<CandidateSignal>> entry : orderedAtoms) {
            AtomKey key = entry.getKey();
            List<CandidateSignal> provenance = entry.getValue();
            events.add(new TemporalAtomicEvent(events.size(), key.timestampMs(), key.type(),
                    provenance.isEmpty() ? "CANONICAL_CANDIDATE" : provenance.stream()
                            .map(signal -> signal.type().name()).distinct()
                            .reduce((first, second) -> first + ", " + second).orElse("UNKNOWN"),
                    provenance.isEmpty() ? candidate.score()
                            : provenance.stream().mapToDouble(CandidateSignal::confidence).max().orElse(0),
                    provenance.isEmpty() ? List.of(TemporalEvidenceFamily.CLASSIFICATION)
                            : provenance.stream().flatMap(signal -> evidenceFamilies(signal).stream())
                            .distinct().toList(),
                    candidate.sourceCandidateIds(), isNegativeOutcome(key.type()),
                    provenance.isEmpty() ? "Persisted candidate is a detected GOAL at its canonical trigger"
                            : String.join("; ", provenance.stream().map(signal ->
                            signal.timestampMs() == key.timestampMs() ? signal.evidence()
                                    : "Evidence timestamp " + signal.timestampMs() + " ms: "
                                    + signal.evidence()).distinct().toList())));
        }
        List<String> negativeEvidence = events.stream()
                .filter(TemporalAtomicEvent::negativeOutcome)
                .map(event -> event.type() + " at " + event.timestampMs() + " ms: " + event.evidence())
                .distinct().toList();
        return new TemporalEventSequence(assessment(candidate), events,
                relationships(events, candidate, settings), negativeEvidence);
    }

    private static String assessment(CandidateEvent candidate) {
        if (candidate.status() == CandidateEventStatus.DETECTED) {
            return candidate.eventType() == FootballEventType.GOAL
                    ? "CANONICAL_GOAL" : "DETECTED_EVENT";
        }
        if (candidate.rejectionReasons().stream().anyMatch(reason ->
                reason.contains("REPLAY_OF_EXISTING_GOAL") || reason.contains("DUPLICATE_EVENT"))) {
            return "REPLAY_OR_DUPLICATE";
        }
        if (!candidate.rejectionReasons().isEmpty()) {
            return "REJECTED_HYPOTHESIS";
        }
        return "UNRESOLVED_HYPOTHESIS";
    }

    private static List<TemporalEventRelationship> relationships(List<TemporalAtomicEvent> events,
                                                                   CandidateEvent candidate,
                                                                   CandidateDetectionQualitySettings settings) {
        List<TemporalEventRelationship> relationships = new ArrayList<>();
        for (int index = 1; index < events.size(); index++) {
            TemporalAtomicEvent previous = events.get(index - 1);
            TemporalAtomicEvent current = events.get(index);
            relationships.add(new TemporalEventRelationship(previous.ordinal(), current.ordinal(),
                    TemporalRelationshipType.PRECEDES,
                    "Ordered by source evidence timestamps"));
        }
        for (TemporalAtomicEvent current : events) {
            if (current.negativeOutcome()) {
                nearestPreceding(events, current, TemporalAtomicEventType.SHOT, settings.contextAttachWindowMs())
                        .ifPresent(shot -> relationships.add(new TemporalEventRelationship(shot.ordinal(),
                                current.ordinal(), TemporalRelationshipType.OUTCOME_OF,
                                "Explicit non-scoring evidence is linked to the nearest preceding shot")));
            }
            if (current.type() == TemporalAtomicEventType.CONTINUED_PLAY) {
                nearestPreceding(events, current, TemporalAtomicEventType.ATTACK,
                        settings.contextAttachWindowMs()).ifPresent(attack ->
                        relationships.add(new TemporalEventRelationship(attack.ordinal(), current.ordinal(),
                                TemporalRelationshipType.OUTCOME_OF,
                                "Continued-play evidence follows the nearest recorded attack")));
            }
            if (current.type() == TemporalAtomicEventType.PENALTY_ATTEMPT) {
                nearestPreceding(events, current, TemporalAtomicEventType.PENALTY_AWARDED,
                        settings.contextAttachWindowMs()).ifPresent(award ->
                        relationships.add(new TemporalEventRelationship(award.ordinal(), current.ordinal(),
                                TemporalRelationshipType.SUPPORTS,
                                "Penalty-attempt evidence follows a penalty-awarded cue")));
            }
        }
        TemporalAtomicEvent goal = events.stream()
                .filter(event -> event.type() == TemporalAtomicEventType.GOAL)
                .findFirst().orElse(null);
        if (goal != null) {
            TemporalAtomicEvent shot = events.stream()
                    .filter(event -> event.type() == TemporalAtomicEventType.SHOT
                            && event.timestampMs() <= goal.timestampMs()
                            && goal.timestampMs() - event.timestampMs() <= settings.contextAttachWindowMs())
                    .max(Comparator.comparingLong(TemporalAtomicEvent::timestampMs))
                    .filter(event -> !hasPhaseFence(events, event, goal))
                    .orElse(null);
            if (shot != null) {
                relationships.add(new TemporalEventRelationship(shot.ordinal(), goal.ordinal(),
                        TemporalRelationshipType.SUPPORTS,
                        "Scoring evidence follows a temporally adjacent shot without an intervening phase fence"));
            }
            TemporalAtomicEvent attack = events.stream()
                    .filter(event -> event.type() == TemporalAtomicEventType.ATTACK
                            || event.type() == TemporalAtomicEventType.DANGEROUS_ATTACK)
                    .filter(event -> event.timestampMs() <= goal.timestampMs()
                            && goal.timestampMs() - event.timestampMs()
                            <= settings.attackBuildupMaximumLeadMs())
                    .filter(event -> !hasPhaseFence(events, event, goal))
                    .max(Comparator.comparingLong(TemporalAtomicEvent::timestampMs))
                    .orElse(null);
            if (attack != null) {
                relationships.add(new TemporalEventRelationship(attack.ordinal(), goal.ordinal(),
                        TemporalRelationshipType.SUPPORTS,
                        "Attack evidence precedes the canonical goal within the configured buildup window"));
            }
        }
        TemporalAtomicEvent replay = events.stream()
                .filter(event -> event.type() == TemporalAtomicEventType.REPLAY_INTRODUCTION
                        || event.type() == TemporalAtomicEventType.REPLAY_EVENT
                        || event.type() == TemporalAtomicEventType.RETROSPECTIVE_REFERENCE)
                .findFirst().orElse(null);
        boolean replayWasAssociated = candidate.rejectionReasons().stream().anyMatch(reason ->
                reason.contains("REPLAY_OF_EXISTING_GOAL") || reason.contains("DUPLICATE_EVENT"))
                || candidate.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && (signal.evidence().startsWith("REPLAY_EVIDENCE_FOR_LIVE_EVENT;")
                        || signal.evidence().startsWith("REPLAY_OF_EXISTING_GOAL; DUPLICATE_EVENT;")));
        if (goal != null && replay != null && replayWasAssociated) {
            relationships.add(new TemporalEventRelationship(replay.ordinal(), goal.ordinal(),
                    TemporalRelationshipType.REPLAY_OF,
                    "Explicit candidate association evidence links replay context to this GOAL"));
        }
        if (candidate.status() == CandidateEventStatus.REJECTED) {
            events.stream().filter(event -> event.type() == TemporalAtomicEventType.REJECTION)
                    .forEach(event -> {
                        TemporalAtomicEvent nearest = events.stream()
                                .filter(other -> other.ordinal() != event.ordinal())
                                .min(Comparator.comparingLong(other ->
                                        Math.abs(other.timestampMs() - event.timestampMs())))
                                .orElse(null);
                        if (nearest != null) {
                            relationships.add(new TemporalEventRelationship(nearest.ordinal(), event.ordinal(),
                                    TemporalRelationshipType.CONTRADICTS,
                                    "Recorded candidate rejection evidence"));
                        }
                    });
        }
        return relationships;
    }

    private static boolean hasPhaseFence(List<TemporalAtomicEvent> events, TemporalAtomicEvent from,
                                         TemporalAtomicEvent to) {
        return events.stream().anyMatch(event -> event.timestampMs() > from.timestampMs()
                && event.timestampMs() < to.timestampMs()
                && (event.type() == TemporalAtomicEventType.RESTART
                || event.type() == TemporalAtomicEventType.GOAL_KICK
                || event.type() == TemporalAtomicEventType.INJURY
                || event.type() == TemporalAtomicEventType.PHASE_BOUNDARY
                || event.type() == TemporalAtomicEventType.REPLAY_INTRODUCTION
                || event.type() == TemporalAtomicEventType.REPLAY_EVENT
                || event.type() == TemporalAtomicEventType.RETROSPECTIVE_REFERENCE));
    }

    private static java.util.Optional<TemporalAtomicEvent> nearestPreceding(
            List<TemporalAtomicEvent> events, TemporalAtomicEvent current,
            TemporalAtomicEventType sourceType, long maximumGapMs) {
        return events.stream()
                .filter(event -> event.type() == sourceType && event.timestampMs() <= current.timestampMs()
                        && current.timestampMs() - event.timestampMs() <= maximumGapMs)
                .max(Comparator.comparingLong(TemporalAtomicEvent::timestampMs))
                .filter(event -> !hasPhaseFence(events, event, current));
    }

    private static TemporalAtomicEventType atomicType(CandidateSignal signal, CandidateEvent candidate) {
        String outcome = outcome(signal.evidence());
        if (signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                && (signal.evidence().startsWith("REPLAY_OF_EXISTING_GOAL; DUPLICATE_EVENT;")
                || signal.evidence().startsWith("REPLAY_EVIDENCE_FOR_LIVE_EVENT;"))) {
            return TemporalAtomicEventType.REPLAY_EVENT;
        }
        if (signal.type() == CandidateSignalType.SHOT_OUTCOME_CONTEXT) {
            return outcome == null ? TemporalAtomicEventType.OTHER
                    : switch (outcome) {
                        case "SAVE" -> TemporalAtomicEventType.SAVE;
                        case "BLOCK" -> TemporalAtomicEventType.BLOCK;
                        case "CLEARANCE" -> TemporalAtomicEventType.CLEARANCE;
                        case "CORNER" -> TemporalAtomicEventType.CORNER;
                        case "WIDE" -> TemporalAtomicEventType.WIDE;
                        case "POST" -> TemporalAtomicEventType.POST;
                        case "CONTINUED_PLAY" -> TemporalAtomicEventType.CONTINUED_PLAY;
                        case "PENALTY_AWARDED" -> TemporalAtomicEventType.PENALTY_AWARDED;
                        case "PENALTY_ATTEMPT" -> TemporalAtomicEventType.PENALTY_ATTEMPT;
                        default -> TemporalAtomicEventType.OTHER;
                    };
        }
        if (signal.eventType() == FootballEventType.MISSED_PENALTY) {
            return TemporalAtomicEventType.MISSED_PENALTY;
        }
        if (signal.eventType() == FootballEventType.FOUL) return TemporalAtomicEventType.FOUL;
        if (signal.eventType() == FootballEventType.YELLOW_CARD
                || signal.eventType() == FootballEventType.RED_CARD) return TemporalAtomicEventType.CARD;
        if (signal.eventType() == FootballEventType.CELEBRATION) {
            return TemporalAtomicEventType.CELEBRATION;
        }
        if (signal.type() == CandidateSignalType.TRANSCRIPT_EVENT) {
            if (signal.eventType() == FootballEventType.ATTACK
                    || signal.eventType() == FootballEventType.COUNTER_ATTACK) {
                return TemporalAtomicEventType.ATTACK;
            }
            if (signal.eventType() == FootballEventType.BIG_CHANCE) {
                return TemporalAtomicEventType.DANGEROUS_ATTACK;
            }
            if (signal.eventType() == FootballEventType.SHOT) return TemporalAtomicEventType.SHOT;
            if (signal.eventType() == FootballEventType.SAVE) return TemporalAtomicEventType.SAVE;
            if (signal.eventType() == FootballEventType.NEAR_MISS) {
                return outcome != null && outcome.equals("POST")
                        ? TemporalAtomicEventType.POST : TemporalAtomicEventType.WIDE;
            }
        }
        if (signal.eventType() == FootballEventType.GOAL
                && (signal.type() == CandidateSignalType.TRANSCRIPT_GOAL
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT
                || signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                || signal.type() == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS)) {
            if (candidate.status() == CandidateEventStatus.DETECTED
                    || candidate.status() == CandidateEventStatus.AI_ANALYZED) {
                return TemporalAtomicEventType.GOAL;
            }
            return candidate.replayProbability() > 0
                    ? TemporalAtomicEventType.REPLAY_EVENT : TemporalAtomicEventType.GOAL_HYPOTHESIS;
        }
        if (signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION) {
            return TemporalAtomicEventType.RECONSTRUCTED_ACTION;
        }
        if (signal.eventType() == FootballEventType.PENALTY
                && (signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                || signal.type() == CandidateSignalType.TRANSCRIPT_EVENT)) {
            return signal.type() == CandidateSignalType.TRANSCRIPT_PENALTY
                    ? TemporalAtomicEventType.PENALTY_ATTEMPT : TemporalAtomicEventType.PENALTY_AWARDED;
        }
        return switch (signal.type()) {
            case SCORE_STATE -> TemporalAtomicEventType.SCORE_STATE;
            case SCORE_STATE_TRANSITION -> TemporalAtomicEventType.SCORE_TRANSITION;
            case REPLAY_CONTEXT -> TemporalAtomicEventType.REPLAY_INTRODUCTION;
            case RETROSPECTIVE_CONTEXT -> TemporalAtomicEventType.REPLAY_EVENT;
            case RESTART_CONTEXT -> TemporalAtomicEventType.RESTART;
            case GOAL_KICK_CONTEXT -> TemporalAtomicEventType.GOAL_KICK;
            case INJURY_CONTEXT -> TemporalAtomicEventType.INJURY;
            case UNRELATED_ACTION_CONTEXT -> TemporalAtomicEventType.PHASE_BOUNDARY;
            case TRANSCRIPT_SHOT -> TemporalAtomicEventType.SHOT;
            case ATTACK_BUILDUP -> TemporalAtomicEventType.ATTACK;
            case TRANSCRIPT_EMPHASIS, TRANSCRIPT_REPETITION, POST_EVENT_COMMENTARY ->
                    TemporalAtomicEventType.COMMENTATOR_REACTION;
            case AUDIO_ENERGY_RISE, AUDIO_SPIKE, CROWD_REACTION_PROXY ->
                    TemporalAtomicEventType.AUDIO_INTENSITY_RISE;
            case AUDIO_SUSTAINED, HIGH_EXCITEMENT, VOICE_EXCITEMENT ->
                    TemporalAtomicEventType.AUDIO_SUSTAINED;
            case SPEECH_RATE_SPIKE -> TemporalAtomicEventType.SPEECH_RATE_RISE;
            case PITCH_RISE, PITCH_VARIANCE -> TemporalAtomicEventType.PITCH_RISE;
            case REJECTION_REASON -> TemporalAtomicEventType.REJECTION;
            case EVENT_BOUNDARY, EVENT_AFTERGLOW -> TemporalAtomicEventType.CLIP_BOUNDARY;
            case TRANSCRIPT_CARD -> TemporalAtomicEventType.CARD;
            case LIVE_EVENT_CONTEXT -> TemporalAtomicEventType.OTHER;
            case EVENT_ASSOCIATION, EVENT_MERGE -> TemporalAtomicEventType.ASSOCIATION;
            default -> null;
        };
    }

    private static boolean isNegativeOutcome(TemporalAtomicEventType type) {
        return type == TemporalAtomicEventType.SAVE || type == TemporalAtomicEventType.BLOCK
                || type == TemporalAtomicEventType.CLEARANCE || type == TemporalAtomicEventType.CORNER
                || type == TemporalAtomicEventType.WIDE
                || type == TemporalAtomicEventType.POST || type == TemporalAtomicEventType.CONTINUED_PLAY
                || type == TemporalAtomicEventType.MISSED_PENALTY;
    }

    private static List<TemporalEvidenceFamily> evidenceFamilies(CandidateSignal signal) {
        if (signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                && (signal.evidence().startsWith("REPLAY_OF_EXISTING_GOAL;")
                || signal.evidence().startsWith("REPLAY_EVIDENCE_FOR_LIVE_EVENT;"))) {
            return List.of(TemporalEvidenceFamily.ASSOCIATION, TemporalEvidenceFamily.REPLAY_CONTEXT);
        }
        return switch (signal.type()) {
            case SCORE_STATE, SCORE_STATE_TRANSITION -> List.of(TemporalEvidenceFamily.SCORE_STATE);
            case REPLAY_CONTEXT, RETROSPECTIVE_CONTEXT -> List.of(TemporalEvidenceFamily.REPLAY_CONTEXT);
            case RESTART_CONTEXT -> List.of(TemporalEvidenceFamily.RESTART_CONTEXT);
            case INJURY_CONTEXT -> List.of(TemporalEvidenceFamily.INJURY_CONTEXT);
            case EVENT_RECONSTRUCTION -> List.of(TemporalEvidenceFamily.RECONSTRUCTION);
            case EVENT_ASSOCIATION, EVENT_MERGE -> List.of(TemporalEvidenceFamily.ASSOCIATION);
            case AUDIO_SPIKE, AUDIO_SUSTAINED, AUDIO_ENERGY_RISE, VOICE_EXCITEMENT,
                    HIGH_EXCITEMENT, PITCH_RISE, PITCH_VARIANCE, SPEECH_RATE_SPIKE,
                    CROWD_REACTION_PROXY -> List.of(TemporalEvidenceFamily.AUDIO);
            case REJECTION_REASON -> List.of(TemporalEvidenceFamily.CLASSIFICATION);
            default -> List.of(TemporalEvidenceFamily.TRANSCRIPT);
        };
    }

    private static String outcome(String evidence) {
        if (evidence == null) return null;
        String prefix = "OUTCOME=";
        int start = evidence.indexOf(prefix);
        if (start < 0) return null;
        int valueStart = start + prefix.length();
        int end = evidence.indexOf(';', valueStart);
        return evidence.substring(valueStart, end < 0 ? evidence.length() : end)
                .trim().toUpperCase(Locale.ROOT);
    }

    private static java.util.Optional<Long> associatedReplayTimestamp(String evidence) {
        Matcher matcher = ASSOCIATED_REPLAY_TIMESTAMP.matcher(evidence);
        if (matcher.find()) {
            return java.util.Optional.of(Long.parseLong(matcher.group(1)));
        }
        matcher = REPLAY_CUE_TIMESTAMP.matcher(evidence);
        return matcher.find() ? java.util.Optional.of(Long.parseLong(matcher.group(1)))
                : java.util.Optional.empty();
    }

    private record AtomKey(long timestampMs, TemporalAtomicEventType type) {
    }
}
