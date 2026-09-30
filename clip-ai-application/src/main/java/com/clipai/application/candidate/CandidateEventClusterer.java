package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class CandidateEventClusterer {
    private static final Set<String> CONTEXT_STOP_WORDS = Set.of(
            "a", "al", "an", "and", "are", "as", "at", "au", "avec", "been", "but", "by", "com",
            "da", "de", "del", "des", "do", "dos", "du", "el", "en", "est", "et", "for", "from",
            "il", "in", "is", "it", "la", "le", "les", "los", "mais", "na", "no", "nos", "of",
            "on", "or", "os", "ou", "para", "por", "que", "se", "son", "the", "to", "un", "une",
            "was", "with", "y");
    private static final Set<String> REPLAY_IDENTITY_STOP_WORDS = Set.of(
            "goal", "goals", "score", "scores", "scored", "replay", "crowd",
            "team", "match", "but", "marque", "marquer", "egalisation");

    private final CandidateEventClusteringSettings settings;
    private final CandidateDetectionQualitySettings qualitySettings;

    public CandidateEventClusterer(CandidateEventClusteringSettings settings) {
        this(settings, CandidateDetectionQualitySettings.defaults());
    }

    public CandidateEventClusterer(CandidateEventClusteringSettings settings,
                                   CandidateDetectionQualitySettings qualitySettings) {
        this.settings = settings;
        this.qualitySettings = qualitySettings;
    }

    public List<CandidateEvent> cluster(List<CandidateEvent> candidates) {
        List<CandidateEvent> ordered = candidates.stream()
                .sorted(Comparator.comparingLong(CandidateEvent::triggerTimestampMs)
                        .thenComparing(candidate -> candidate.eventType().name())
                        .thenComparing(candidate -> candidate.id().toString()))
                .toList();
        List<List<CandidateEvent>> clusters = new ArrayList<>();
        for (CandidateEvent candidate : ordered) {
            List<CandidateEvent> matching = candidate.status() == CandidateEventStatus.DETECTED
                    ? findMatchingCluster(candidate, clusters)
                    : findReplaySupportCluster(candidate, clusters);
            if (matching == null) {
                clusters.add(new ArrayList<>(List.of(candidate)));
            } else {
                matching.add(candidate);
            }
        }
        return clusters.stream().map(this::canonicalize)
                .sorted(Comparator.comparing((CandidateEvent candidate) ->
                                candidate.status() != CandidateEventStatus.DETECTED)
                        .thenComparing(Comparator.comparingDouble(CandidateEvent::score).reversed())
                        .thenComparingLong(CandidateEvent::startTimeMs)
                        .thenComparing(candidate -> candidate.eventType().name()))
                .toList();
    }

    private List<CandidateEvent> findMatchingCluster(CandidateEvent candidate,
                                                      List<List<CandidateEvent>> clusters) {
        for (int index = clusters.size() - 1; index >= 0; index--) {
            List<CandidateEvent> cluster = clusters.get(index);
            if (cluster.getFirst().status() != CandidateEventStatus.DETECTED
                    || cluster.getFirst().eventType() != candidate.eventType()) {
                continue;
            }
            long clusterStart = cluster.stream().mapToLong(CandidateEvent::triggerTimestampMs).min().orElseThrow();
            boolean sharedGoalAction = candidate.eventType() == FootballEventType.GOAL
                    && cluster.stream().anyMatch(member -> sameGoalAction(candidate, member));
            if (candidate.triggerTimestampMs() - clusterStart > triggerWindow(candidate.eventType())
                    && !sharedGoalAction) {
                continue;
            }
            if (cluster.stream().allMatch(member -> representsSameEvent(candidate, member))) {
                return cluster;
            }
        }
        return null;
    }

    private List<CandidateEvent> findReplaySupportCluster(CandidateEvent candidate,
                                                           List<List<CandidateEvent>> clusters) {
        CandidateSignal replayCue = candidate.signals().stream()
                .filter(signal -> (signal.type() == CandidateSignalType.REPLAY_CONTEXT
                        || signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT)
                        && !signal.evidence().startsWith("Replay probability includes")
                        && !signal.evidence().startsWith("Goal candidate repeats nearby")
                        && signal.confidence() >= qualitySettings.replayProbabilityThreshold())
                .max(Comparator.comparingDouble(CandidateSignal::confidence))
                .orElse(null);
        if (candidate.eventType() != FootballEventType.GOAL || replayCue == null) {
            return null;
        }
        List<ReplayAssociation> matches = new ArrayList<>();
        for (int index = clusters.size() - 1; index >= 0; index--) {
            List<CandidateEvent> cluster = clusters.get(index);
            CandidateEvent liveGoal = cluster.stream()
                    .filter(event -> event.status() == CandidateEventStatus.DETECTED
                            && event.eventType() == FootballEventType.GOAL
                            && event.score() >= 0.70
                            && event.rejectionReasons().isEmpty())
                    .max(Comparator.comparingLong(CandidateEvent::triggerTimestampMs))
                    .orElse(null);
            if (liveGoal == null || liveGoal.triggerTimestampMs() >= candidate.triggerTimestampMs()) {
                continue;
            }
            double similarity = contextSimilarity(liveGoal.transcriptContext(), candidate.transcriptContext());
            boolean scoreEvidence = sharesScoreState(liveGoal, candidate);
            boolean repeatedAction = Math.max(similarity,
                    replayContextContainment(liveGoal.transcriptContext(), candidate.transcriptContext()))
                    >= Math.max(0.70, settings.minimumContextSimilarity())
                    && sharedReplayIdentityTokens(liveGoal.transcriptContext(),
                    candidate.transcriptContext()) >= 3;
            boolean newScoreTransition = candidate.signals().stream().anyMatch(signal ->
                    signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION
                            && signal.timestampMs() >= candidate.triggerTimestampMs()
                            && signal.timestampMs() - candidate.triggerTimestampMs() <= 30_000);
            if (!newScoreTransition && repeatedAction) {
                matches.add(new ReplayAssociation(cluster, liveGoal, similarity, scoreEvidence));
            }
        }
        return matches.stream()
                .max(Comparator.comparingDouble(ReplayAssociation::contextSimilarity)
                        .thenComparing(ReplayAssociation::scoreEvidence)
                        .thenComparingLong(match -> match.liveGoal().triggerTimestampMs()))
                .map(ReplayAssociation::cluster)
                .orElse(null);
    }

    private boolean representsSameEvent(CandidateEvent first, CandidateEvent second) {
        long triggerGap = Math.abs(first.triggerTimestampMs() - second.triggerTimestampMs());
        if (first.eventType() == FootballEventType.GOAL
                && hasConflictingScoreTransitions(first, second)) {
            return false;
        }
        if (first.eventType() == FootballEventType.GOAL && sameGoalAction(first, second)) {
            return true;
        }
        if (first.eventType() == FootballEventType.GOAL) {
            return triggerGap == 0;
        }
        if (triggerGap > triggerWindow(first.eventType())
                || first.endTimeMs() < second.startTimeMs()
                || second.endTimeMs() < first.startTimeMs()) {
            return false;
        }
        if (triggerGap == 0) {
            return true;
        }
        double contextSimilarity = contextSimilarity(first.transcriptContext(), second.transcriptContext());
        if (triggerGap <= 3_000 && contextSimilarity >= settings.minimumContextSimilarity()) {
            return true;
        }
        if (triggerGap <= 5_000 && sharesTranscriptAnchor(first, second)
                && contextSimilarity >= settings.minimumContextSimilarity() * 0.55) {
            return true;
        }
        if (sharesTranscriptAnchor(first, second)
                && contextSimilarity >= Math.max(0.80, settings.minimumContextSimilarity())) {
            return true;
        }
        return isGoalOrCard(first.eventType()) && sharesAudioBurst(first, second)
                && contextSimilarity >= settings.minimumContextSimilarity() * 0.7;
    }

    private boolean sameGoalAction(CandidateEvent first, CandidateEvent second) {
        long firstActionMs = goalActionTimestamp(first);
        long secondActionMs = goalActionTimestamp(second);
        if (firstActionMs >= 0 && secondActionMs >= 0
                && Math.abs(firstActionMs - secondActionMs) <= 3_000) {
            return true;
        }
        return sharesScoreTransition(first, second)
                && contextSimilarity(first.transcriptContext(), second.transcriptContext())
                >= Math.max(0.65, settings.minimumContextSimilarity());
    }

    private static boolean sharesScoreTransition(CandidateEvent first, CandidateEvent second) {
        return first.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .anyMatch(firstTransition -> second.signals().stream()
                        .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                        .anyMatch(secondTransition -> firstTransition.timestampMs()
                                == secondTransition.timestampMs()
                                && firstTransition.evidence().equals(secondTransition.evidence())));
    }

    private static long goalActionTimestamp(CandidateEvent candidate) {
        return candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.eventType() == FootballEventType.GOAL)
                .mapToLong(CandidateSignal::timestampMs)
                .findFirst()
                .orElseGet(() -> candidate.signals().stream()
                        .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_SHOT
                                && signal.eventType() == FootballEventType.SHOT
                                && isInferredGoal(candidate))
                        .mapToLong(CandidateSignal::timestampMs)
                        .findFirst().orElse(-1L));
    }

    private static boolean isInferredGoal(CandidateEvent candidate) {
        return candidate.signals().stream()
                .anyMatch(signal -> signal.eventType() == FootballEventType.GOAL
                        && (signal.type() == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS
                        || signal.type() == CandidateSignalType.TRANSCRIPT_GOAL
                        && signal.evidence().contains("Inferred live goal hypothesis")));
    }

    private CandidateEvent canonicalize(List<CandidateEvent> cluster) {
        if (cluster.size() == 1) {
            return cluster.getFirst();
        }
        List<CandidateEvent> liveEvents = cluster.stream()
                .filter(candidate -> candidate.status() == CandidateEventStatus.DETECTED)
                .toList();
        CandidateEvent representative = liveEvents.stream()
                .sorted(Comparator.comparingDouble(CandidateEvent::score).reversed()
                        .thenComparingLong(CandidateEvent::triggerTimestampMs)
                        .thenComparing(candidate -> candidate.id().toString()))
                .findFirst().orElseThrow();
        List<CandidateEvent> replaySupport = cluster.stream()
                .filter(candidate -> candidate.status() != CandidateEventStatus.DETECTED)
                .toList();
        long startTimeMs = representative.eventType() == FootballEventType.GOAL
                ? selectGoalStart(liveEvents, representative)
                : liveEvents.stream().mapToLong(CandidateEvent::startTimeMs).min().orElseThrow();
        long endTimeMs = representative.eventType() == FootballEventType.GOAL
                ? selectGoalEnd(liveEvents, representative)
                : liveEvents.stream().mapToLong(CandidateEvent::endTimeMs).max().orElseThrow();
        startTimeMs = Math.min(startTimeMs, representative.triggerTimestampMs());
        endTimeMs = Math.max(endTimeMs, representative.triggerTimestampMs());
        long maximumDuration = settings.maximumEventDurationMs();
        if (endTimeMs - startTimeMs > maximumDuration) {
            startTimeMs = Math.max(0, Math.min(startTimeMs,
                    representative.triggerTimestampMs() - maximumDuration / 2));
            endTimeMs = Math.min(endTimeMs, safeAdd(startTimeMs, maximumDuration));
            if (endTimeMs < representative.triggerTimestampMs()) {
                endTimeMs = representative.triggerTimestampMs();
                startTimeMs = Math.max(0, endTimeMs - maximumDuration);
            }
        }

        final long mergedStartTimeMs = startTimeMs;
        final long mergedEndTimeMs = endTimeMs;
        LinkedHashSet<CandidateSignal> uniqueSignals = new LinkedHashSet<>();
        liveEvents.stream().flatMap(candidate -> candidate.signals().stream())
                .filter(signal -> signal.timestampMs() >= mergedStartTimeMs
                        && signal.timestampMs() <= mergedEndTimeMs)
                .forEach(uniqueSignals::add);
        replaySupport.forEach(candidate -> uniqueSignals.add(new CandidateSignal(
                CandidateSignalType.EVENT_ASSOCIATION, representative.eventType(),
                candidate.replayProbability(), representative.triggerTimestampMs(),
                "REPLAY_OF_EXISTING_GOAL; DUPLICATE_EVENT; replay-only GOAL candidate "
                        + candidate.id() + " at " + candidate.triggerTimestampMs()
                        + " ms was associated with canonical goal " + representative.id()
                        + " without extending its live clip window")));
        List<UUID> sourceIds = new ArrayList<>();
        sourceIds.add(representative.id());
        cluster.stream().map(CandidateEvent::id).filter(id -> !id.equals(representative.id()))
                .sorted(Comparator.comparing(UUID::toString)).forEach(sourceIds::add);
        long triggerSpread = liveEvents.stream().mapToLong(CandidateEvent::triggerTimestampMs).max().orElseThrow()
                - liveEvents.stream().mapToLong(CandidateEvent::triggerTimestampMs).min().orElseThrow();
        String reason = "Merged " + liveEvents.size() + " " + representative.eventType()
                + " detections with overlapping windows, " + triggerSpread
                + " ms trigger spread, and shared transcript context or event evidence";
        if (!replaySupport.isEmpty()) {
            reason += "; associated " + replaySupport.size()
                    + " rejected replay-only goal detection(s) as replay evidence";
        }
        uniqueSignals.add(new CandidateSignal(CandidateSignalType.EVENT_MERGE,
                representative.eventType(), 1.0, representative.triggerTimestampMs(), reason));
        String context = mergeContexts(liveEvents);
        return representative.withCanonicalDetails(startTimeMs, endTimeMs,
                List.copyOf(uniqueSignals), context, sourceIds, reason);
    }

    private static long selectGoalStart(List<CandidateEvent> liveEvents, CandidateEvent representative) {
        int bestBoundaryQuality = liveEvents.stream()
                .mapToInt(CandidateEventClusterer::goalStartBoundaryQuality)
                .max().orElse(0);
        if (bestBoundaryQuality == 0) {
            return representative.startTimeMs();
        }
        return liveEvents.stream()
                .filter(candidate -> goalStartBoundaryQuality(candidate) == bestBoundaryQuality)
                .mapToLong(CandidateEvent::startTimeMs)
                .min().orElse(representative.startTimeMs());
    }

    private static int goalStartBoundaryQuality(CandidateEvent candidate) {
        return candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.EVENT_BOUNDARY)
                .mapToInt(signal -> {
                    String reason = signal.evidence();
                    if (reason.contains("START=PENALTY_SETUP")) {
                        return 5;
                    }
                    if (reason.contains("START=BUILDUP_SIGNAL")) {
                        return 4;
                    }
                    if (reason.contains("START=ATTACK_INTENSITY_RISE")) {
                        return 3;
                    }
                    if (reason.contains("START=DECISIVE_ACTION")) {
                        return 2;
                    }
                    return 0;
                })
                .max().orElse(0);
    }

    private static long selectGoalEnd(List<CandidateEvent> liveEvents, CandidateEvent representative) {
        List<CandidateEvent> hardBoundaryEvents = liveEvents.stream()
                .filter(CandidateEventClusterer::hasHardGoalEndBoundary)
                .toList();
        if (!hardBoundaryEvents.isEmpty()) {
            return hardBoundaryEvents.stream()
                    .mapToLong(CandidateEvent::endTimeMs)
                    .min().orElse(representative.endTimeMs());
        }
        if (hasGoalReactionBoundary(representative)) {
            return representative.endTimeMs();
        }
        return liveEvents.stream()
                .filter(CandidateEventClusterer::hasGoalReactionBoundary)
                .max(Comparator.comparingDouble(CandidateEvent::score)
                        .thenComparing(Comparator.comparingLong(CandidateEvent::triggerTimestampMs).reversed()))
                .map(CandidateEvent::endTimeMs)
                .orElse(representative.endTimeMs());
    }

    private static boolean hasHardGoalEndBoundary(CandidateEvent candidate) {
        return candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.EVENT_AFTERGLOW)
                .anyMatch(signal -> signal.evidence().contains("END=IMMEDIATE_RESTART")
                        || signal.evidence().contains("END=REPLAY_BOUNDARY"));
    }

    private static boolean hasGoalReactionBoundary(CandidateEvent candidate) {
        return candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.EVENT_AFTERGLOW)
                .anyMatch(signal -> signal.evidence().contains("END=GOAL_REACTION"));
    }

    private long triggerWindow(FootballEventType eventType) {
        return switch (eventType) {
            case GOAL -> settings.goalTriggerWindowMs();
            case YELLOW_CARD, RED_CARD -> settings.cardTriggerWindowMs();
            case SHOT, NEAR_MISS -> settings.shotTriggerWindowMs();
            default -> settings.otherTriggerWindowMs();
        };
    }

    private static boolean sharesTranscriptAnchor(CandidateEvent first, CandidateEvent second) {
        Set<String> firstAnchors = transcriptAnchors(first);
        firstAnchors.retainAll(transcriptAnchors(second));
        return !firstAnchors.isEmpty();
    }

    private static boolean sharesScoreState(CandidateEvent first, CandidateEvent second) {
        Set<String> firstScores = scoreStates(first);
        firstScores.retainAll(scoreStates(second));
        return !firstScores.isEmpty();
    }

    private static boolean hasConflictingScoreTransitions(CandidateEvent first, CandidateEvent second) {
        Set<String> firstResults = scoreTransitionResults(first);
        Set<String> secondResults = scoreTransitionResults(second);
        return !firstResults.isEmpty() && !secondResults.isEmpty()
                && firstResults.stream().noneMatch(secondResults::contains);
    }

    private static Set<String> scoreTransitionResults(CandidateEvent candidate) {
        Set<String> results = new HashSet<>();
        candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .forEach(signal -> {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("\\bto\\s+(\\d{1,2}-\\d{1,2})\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(signal.evidence());
                    while (matcher.find()) {
                        results.add(matcher.group(1));
                    }
                });
        return results;
    }

    private static int sharedReplayIdentityTokens(String first, String second) {
        Set<String> firstWords = replayIdentityWords(first);
        firstWords.retainAll(replayIdentityWords(second));
        return firstWords.size();
    }

    private static double replayContextContainment(String first, String second) {
        Set<String> firstWords = replayIdentityWords(first);
        Set<String> secondWords = replayIdentityWords(second);
        if (firstWords.isEmpty() || secondWords.isEmpty()) {
            return 0;
        }
        firstWords.retainAll(secondWords);
        return (double) firstWords.size()
                / Math.min(replayIdentityWords(first).size(), replayIdentityWords(second).size());
    }

    private static Set<String> replayIdentityWords(String context) {
        Set<String> words = new HashSet<>(contextWords(context));
        words.removeIf(word -> word.length() < 4 || REPLAY_IDENTITY_STOP_WORDS.contains(word));
        return words;
    }

    private static Set<String> scoreStates(CandidateEvent candidate) {
        Set<String> scores = new HashSet<>();
        candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE
                        || signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .forEach(signal -> {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("\\b\\d{1,2}-\\d{1,2}\\b").matcher(signal.evidence());
                    while (matcher.find()) {
                        scores.add(matcher.group());
                    }
                });
        return scores;
    }

    private static Set<String> transcriptAnchors(CandidateEvent candidate) {
        Set<String> anchors = new HashSet<>();
        candidate.signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.evidence().startsWith("Football phrase match: "))
                .forEach(signal -> anchors.add(TranscriptTextNormalizer.normalize(
                        signal.evidence().substring("Football phrase match: ".length()))));
        return anchors;
    }

    private static boolean sharesAudioBurst(CandidateEvent first, CandidateEvent second) {
        return first.signals().stream()
                .filter(signal -> isAudioReaction(signal.type()))
                .anyMatch(firstSignal -> second.signals().stream()
                        .filter(signal -> isAudioReaction(signal.type()))
                        .anyMatch(secondSignal -> Math.abs(firstSignal.timestampMs()
                                - secondSignal.timestampMs()) <= 1_500));
    }

    private static boolean isAudioReaction(CandidateSignalType type) {
        return switch (type) {
            case AUDIO_SPIKE, AUDIO_SUSTAINED, VOICE_EXCITEMENT, PITCH_RISE, PITCH_VARIANCE,
                    CROWD_REACTION_PROXY -> true;
            default -> false;
        };
    }

    private static boolean isGoalOrCard(FootballEventType eventType) {
        return eventType == FootballEventType.GOAL || eventType == FootballEventType.YELLOW_CARD
                || eventType == FootballEventType.RED_CARD;
    }

    private static double contextSimilarity(String first, String second) {
        Set<String> firstWords = contextWords(first);
        Set<String> secondWords = contextWords(second);
        if (firstWords.isEmpty() || secondWords.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(firstWords);
        intersection.retainAll(secondWords);
        Set<String> union = new HashSet<>(firstWords);
        union.addAll(secondWords);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> contextWords(String context) {
        if (context == null || context.isBlank()) {
            return Set.of();
        }
        Set<String> words = new HashSet<>();
        for (String token : TranscriptTextNormalizer.normalize(context).split("\\s+")) {
            if (token.length() > 2 && !CONTEXT_STOP_WORDS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    private static String mergeContexts(List<CandidateEvent> cluster) {
        StringBuilder result = new StringBuilder();
        for (CandidateEvent event : cluster) {
            String context = event.transcriptContext();
            if (context == null || context.isBlank() || result.toString().contains(context)) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(" | ");
            }
            result.append(context);
            if (result.length() >= 6_000) {
                return result.substring(0, 6_000);
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private record ReplayAssociation(List<CandidateEvent> cluster, CandidateEvent liveGoal,
                                    double contextSimilarity, boolean scoreEvidence) {
    }
}
