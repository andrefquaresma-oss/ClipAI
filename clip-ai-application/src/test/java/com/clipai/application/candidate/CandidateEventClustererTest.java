package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEventClustererTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final CandidateEventClusterer clusterer =
            new CandidateEventClusterer(CandidateEventClusteringSettings.defaults());

    @Test
    void mergesFourOverlappingCardDetectionsIntoOneCanonicalEventWithProvenance() {
        UUID mediaAssetId = UUID.randomUUID();
        List<CandidateEvent> duplicates = List.of(
                detected(mediaAssetId, FootballEventType.YELLOW_CARD, 90_000, "Foul on Martin, yellow card."),
                detected(mediaAssetId, FootballEventType.YELLOW_CARD, 92_000, "Foul on Martin, yellow card."),
                detected(mediaAssetId, FootballEventType.YELLOW_CARD, 94_000, "Foul on Martin, yellow card."),
                detected(mediaAssetId, FootballEventType.YELLOW_CARD, 96_000, "Foul on Martin, yellow card."));

        List<CandidateEvent> canonical = clusterer.cluster(duplicates);

        assertEquals(1, canonical.size());
        CandidateEvent event = canonical.getFirst();
        assertEquals(FootballEventType.YELLOW_CARD, event.eventType());
        assertEquals(4, event.sourceCandidateIds().size());
        assertTrue(event.sourceCandidateIds().contains(event.id()));
        assertNotNull(event.mergeReason());
        assertTrue(event.mergeReason().contains("Merged 4 YELLOW_CARD detections"));
        assertTrue(event.signals().stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.EVENT_MERGE));
        assertEquals(CandidateEventStatus.DETECTED, event.status());
    }

    @Test
    void keepsTwoDifferentCardIncidentsSeparateDespiteOverlappingWindows() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent first = detected(mediaAssetId, FootballEventType.YELLOW_CARD, 100_000,
                "Foul on Martin, yellow card by referee.");
        CandidateEvent second = detected(mediaAssetId, FootballEventType.YELLOW_CARD, 115_000,
                "Second booking for Alvarez, referee shows card.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(first, second));

        assertEquals(2, canonical.size());
        assertTrue(canonical.stream().allMatch(event -> event.sourceCandidateIds().size() == 1));
        assertTrue(canonical.stream().allMatch(event -> event.mergeReason() == null));
    }

    @Test
    void keepsCloseShotsSeparateWhenTheirContextsDescribeDifferentActions() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent first = detected(mediaAssetId, FootballEventType.SHOT, 100_000,
                "Lamine Yamal curls a shot toward the far post.");
        CandidateEvent second = detected(mediaAssetId, FootballEventType.SHOT, 102_000,
                "Lewandowski fires a separate effort from the edge.");

        assertEquals(2, clusterer.cluster(List.of(first, second)).size());
    }

    @Test
    void keepsTwoDistinctCloseGoalsSeparateWhenActionAndContextDiffer() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent penaltyGoal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Penalty converted by Raphinha after the goalkeeper fouls him.");
        CandidateEvent openPlayGoal = detected(mediaAssetId, FootballEventType.GOAL, 108_000,
                "Lewandowski scores after a separate counterattack and cross.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(penaltyGoal, openPlayGoal));

        assertEquals(2, canonical.size());
        assertTrue(canonical.stream().allMatch(event -> event.sourceCandidateIds().size() == 1));
    }

    @Test
    void doesNotMergeSimilarGoalCommentaryWithoutSameActionEvidence() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent first = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Raphinha scores for Barcelona as the crowd roars.");
        CandidateEvent second = detected(mediaAssetId, FootballEventType.GOAL, 104_000,
                "Raphinha scores for Barcelona as the crowd roars.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(first, second));

        assertEquals(2, canonical.size());
        assertTrue(canonical.stream().allMatch(event -> event.sourceCandidateIds().size() == 1));
    }

    @Test
    void mergesOverlappingGoalDetectionsWithTheSameScoreTransitionAndContext() {
        UUID mediaAssetId = UUID.randomUUID();
        String context = "Lamine Yamal crosses and Raphinha scores for Barcelona.";
        CandidateEvent first = scoredGoal(mediaAssetId, 100_000, 120_000,
                "Transcript score changed from 0-0 to 1-0", context);
        CandidateEvent second = scoredGoal(mediaAssetId, 104_000, 120_000,
                "Transcript score changed from 0-0 to 1-0", context);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(first, second));

        assertEquals(1, canonical.size());
        assertEquals(2, canonical.getFirst().sourceCandidateIds().size());
        assertTrue(canonical.getFirst().signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_MERGE));
    }

    @Test
    void canonicalGoalWindowRetainsRepresentativeTriggerWhenPreferredBoundaryStartsLater() {
        UUID mediaAssetId = UUID.randomUUID();
        String context = "Lamine Yamal crosses and Raphinha scores for Barcelona.";
        CandidateEvent representative = CandidateEvent.detected(mediaAssetId,
                90_000, 130_000, 100_000, FootballEventType.GOAL, 0.95, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.95, 100_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 103_000, "Shared reconstructed action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.55, 90_000, "START=FALLBACK_PRE_ROLL")),
                context, NOW);
        CandidateEvent preferredBoundary = CandidateEvent.detected(mediaAssetId,
                102_000, 135_000, 104_000, FootballEventType.GOAL, 0.84, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.84, 104_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 103_000, "Shared reconstructed action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.82, 102_000, "START=BUILDUP_SIGNAL")),
                context, NOW);

        CandidateEvent canonical = clusterer.cluster(
                List.of(representative, preferredBoundary)).getFirst();

        assertEquals(representative.id(), canonical.id());
        assertEquals(100_000, canonical.startTimeMs());
        assertTrue(canonical.startTimeMs() <= canonical.triggerTimestampMs());
        assertTrue(canonical.endTimeMs() >= canonical.triggerTimestampMs());
    }

    @Test
    void doesNotMergeGoalDetectionsWithDifferentScoreTransitions() {
        UUID mediaAssetId = UUID.randomUUID();
        String context = "Raphinha scores for Barcelona as the crowd roars.";
        CandidateEvent first = scoredGoal(mediaAssetId, 100_000, 120_000,
                "Transcript score changed from 0-0 to 1-0", context);
        CandidateEvent second = scoredGoal(mediaAssetId, 102_000, 122_000,
                "Transcript score changed from 1-0 to 2-0", context);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(first, second));

        assertEquals(2, canonical.size());
        assertTrue(canonical.stream().allMatch(event -> event.sourceCandidateIds().size() == 1));
    }

    @Test
    void keepsSevenDistinctLiveGoalsEvenWhenTheirCommentaryIsIdentical() {
        UUID mediaAssetId = UUID.randomUUID();
        List<CandidateEvent> goals = java.util.stream.LongStream.range(0, 7)
                .mapToObj(index -> detected(mediaAssetId, FootballEventType.GOAL,
                        100_000 + index * 30_000,
                        "The crowd roars as the players celebrate a goal."))
                .toList();

        List<CandidateEvent> canonical = clusterer.cluster(goals);

        assertEquals(7, canonical.stream()
                .filter(event -> event.status() == CandidateEventStatus.DETECTED
                        && event.eventType() == FootballEventType.GOAL)
                .count());
    }

    @Test
    void mergesLaterReplayOnlyGoalIntoEarlierLiveGoalWithoutExtendingClipWindow() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent liveGoal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent replay = CandidateEvent.rejected(mediaAssetId, 145_000, 175_000, 160_000,
                FootballEventType.GOAL, 0.74, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD, FootballEventType.GOAL,
                                0.88, 160_000, "Football phrase match: scores"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.88, 160_000, "Event-specific transcript phrase match"),
                        new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                                0.84, 160_000, "Explicit commentary introduces the replay")),
                "Raphinha scores for Barcelona after a cross into the box.", NOW);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(liveGoal, replay));

        assertEquals(1, canonical.size());
        assertEquals(CandidateEventStatus.DETECTED, canonical.getFirst().status());
        assertEquals(liveGoal.id(), canonical.getFirst().id());
        assertEquals(liveGoal.startTimeMs(), canonical.getFirst().startTimeMs());
        assertEquals(liveGoal.endTimeMs(), canonical.getFirst().endTimeMs());
        assertEquals(List.of(liveGoal.id(), replay.id()), canonical.getFirst().sourceCandidateIds());
        assertTrue(canonical.getFirst().mergeReason().contains("replay-only"));
        assertTrue(canonical.getFirst().signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION));
    }

    @Test
    void leavesReplayGoalUnassociatedWhenContextDoesNotMatchEarlierLiveGoal() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent liveGoal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent replay = CandidateEvent.rejected(mediaAssetId, 145_000, 175_000, 160_000,
                FootballEventType.GOAL, 0.74, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD, FootballEventType.GOAL,
                                0.88, 160_000, "Football phrase match: scores"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.88, 160_000, "Event-specific transcript phrase match"),
                        new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                                0.84, 160_000, "Explicit commentary introduces the replay")),
                "Completely unrelated scene with different players and commentary.", NOW);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(liveGoal, replay));

        assertEquals(2, canonical.size());
        assertEquals(1, canonical.stream()
                .filter(event -> event.status() == CandidateEventStatus.DETECTED).count());
        assertEquals(1, canonical.stream()
                .filter(event -> event.status() == CandidateEventStatus.REJECTED).count());
    }

    @Test
    void associatesMultipleDistantReplayCandidatesWithOneCanonicalGoal() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent liveGoal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent firstReplay = replay(mediaAssetId, 450_000,
                "Replay: Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent secondReplay = replay(mediaAssetId, 900_000,
                "Second replay: Raphinha scores for Barcelona after a cross into the box.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(liveGoal, firstReplay, secondReplay));

        assertEquals(1, canonical.size());
        assertEquals(CandidateEventStatus.DETECTED, canonical.getFirst().status());
        assertEquals(liveGoal.startTimeMs(), canonical.getFirst().startTimeMs());
        assertEquals(liveGoal.endTimeMs(), canonical.getFirst().endTimeMs());
        assertEquals(3, canonical.getFirst().sourceCandidateIds().size());
        assertEquals(2, canonical.getFirst().signals().stream()
                .filter(signal -> signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && signal.evidence().contains("REPLAY_OF_EXISTING_GOAL"))
                .count());
    }

    @Test
    void replayOfOneGoalCannotSuppressASecondGoalWithANewScoreTransition() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent firstGoal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent replay = replay(mediaAssetId, 450_000,
                "Replay: Raphinha scores for Barcelona after a cross into the box.");
        CandidateEvent secondGoal = new CandidateEvent(UUID.randomUUID(), mediaAssetId,
                585_000, 615_000, 600_000, FootballEventType.GOAL, 0.90, List.of(
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 600_000, "Scoring commentary"),
                new CandidateSignal(CandidateSignalType.SCORE_STATE_TRANSITION, null,
                        0.82, 600_000, "Transcript score changed from 1-0 to 2-0")),
                "The crowd roars as Barcelona score after a separate attack.", CandidateEventStatus.DETECTED,
                NOW);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(firstGoal, replay, secondGoal));

        assertEquals(2, canonical.stream()
                .filter(event -> event.status() == CandidateEventStatus.DETECTED)
                .count());
        assertTrue(canonical.stream().anyMatch(event -> event.id().equals(firstGoal.id())
                && event.sourceCandidateIds().contains(replay.id())));
        assertTrue(canonical.stream().anyMatch(event -> event.id().equals(secondGoal.id())
                && event.sourceCandidateIds().size() == 1));
    }

    @Test
    void associatesReplayOfPenaltyGoalWithoutChangingTheLivePenaltyWindow() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent penaltyGoal = detected(mediaAssetId, FootballEventType.GOAL, 1_250_000,
                "Raphinha scores the penalty for Barcelona to open the scoring.");
        CandidateEvent replay = replay(mediaAssetId, 2_100_000,
                "Replay of Raphinha scoring the penalty for Barcelona to open the scoring.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(penaltyGoal, replay));

        assertEquals(1, canonical.size());
        CandidateEvent event = canonical.getFirst();
        assertEquals(penaltyGoal.id(), event.id());
        assertEquals(penaltyGoal.startTimeMs(), event.startTimeMs());
        assertEquals(penaltyGoal.endTimeMs(), event.endTimeMs());
        assertEquals(2, event.sourceCandidateIds().size());
        assertTrue(event.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && signal.evidence().contains("REPLAY_OF_EXISTING_GOAL")));
    }

    @Test
    void mergesLateGoalCommentaryAndShotTimeCandidatesUsingSharedReconstructedAction() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent shotTime = reconstructedGoal(mediaAssetId, 100_000, 98_000);
        CandidateEvent lateCommentary = reconstructedGoal(mediaAssetId, 125_000, 98_000);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(shotTime, lateCommentary));

        assertEquals(1, canonical.size());
        assertEquals(2, canonical.getFirst().sourceCandidateIds().size());
        assertTrue(canonical.getFirst().endTimeMs() - canonical.getFirst().startTimeMs() <= 60_000);
    }

    @Test
    void canonicalGoalMergeKeepsBestBuildupAndEarliestHardClipBoundary() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent reactionBoundedGoal = CandidateEvent.detected(mediaAssetId,
                15_000, 70_000, 52_000, FootballEventType.GOAL, 0.92, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.92, 52_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 50_000, "Shared live action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.82, 15_000, "START=BUILDUP_SIGNAL"),
                        new CandidateSignal(CandidateSignalType.EVENT_AFTERGLOW, FootballEventType.GOAL,
                                0.78, 70_000, "END=GOAL_REACTION")),
                "Attack builds and the same goal is scored.", NOW);
        CandidateEvent restartBoundedGoal = CandidateEvent.detected(mediaAssetId,
                10_000, 65_000, 50_000, FootballEventType.GOAL, 0.84, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.84, 50_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 50_000, "Shared live action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.55, 10_000, "START=FALLBACK_PRE_ROLL"),
                        new CandidateSignal(CandidateSignalType.EVENT_AFTERGLOW, FootballEventType.GOAL,
                                0.90, 65_000, "END=IMMEDIATE_RESTART")),
                "Attack builds and the same goal is scored.", NOW);

        CandidateEvent canonical = clusterer.cluster(List.of(
                reactionBoundedGoal, restartBoundedGoal)).getFirst();

        assertEquals(reactionBoundedGoal.id(), canonical.id());
        assertEquals(15_000, canonical.startTimeMs());
        assertEquals(65_000, canonical.endTimeMs());
        assertTrue(canonical.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_AFTERGLOW
                        && signal.evidence().contains("END=IMMEDIATE_RESTART")));
    }

    @Test
    void canonicalGoalMergeDoesNotWidenRepresentativeReactionWindow() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent representative = CandidateEvent.detected(mediaAssetId,
                15_000, 65_000, 52_000, FootballEventType.GOAL, 0.92, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.92, 52_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 50_000, "Shared live action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.82, 15_000, "START=BUILDUP_SIGNAL"),
                        new CandidateSignal(CandidateSignalType.EVENT_AFTERGLOW, FootballEventType.GOAL,
                                0.78, 65_000, "END=GOAL_REACTION")),
                "Attack builds and the same goal is scored.", NOW);
        CandidateEvent laterWindow = CandidateEvent.detected(mediaAssetId,
                10_000, 75_000, 50_000, FootballEventType.GOAL, 0.84, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.84, 50_000, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.88, 50_000, "Shared live action"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY, FootballEventType.GOAL,
                                0.55, 10_000, "START=FALLBACK_PRE_ROLL"),
                        new CandidateSignal(CandidateSignalType.EVENT_AFTERGLOW, FootballEventType.GOAL,
                                0.78, 75_000, "END=GOAL_REACTION")),
                "Attack builds and the same goal is scored.", NOW);

        CandidateEvent canonical = clusterer.cluster(List.of(representative, laterWindow)).getFirst();

        assertEquals(representative.id(), canonical.id());
        assertEquals(15_000, canonical.startTimeMs());
        assertEquals(65_000, canonical.endTimeMs());
    }

    @Test
    void mergesAudioGoalHypothesisWithTranscriptCandidateReconstructedToSameShot() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent audioGoal = CandidateEvent.detected(mediaAssetId, 85_000, 120_000,
                101_000, FootballEventType.GOAL, 0.88, List.of(
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.88, 101_000, "Audio-led goal hypothesis"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                                0.82, 100_000, "Event-specific transcript phrase match")),
                "A shot is followed by a goal.", NOW);
        CandidateEvent reconstructedGoal = reconstructedGoal(mediaAssetId, 102_000, 100_000);

        List<CandidateEvent> canonical = clusterer.cluster(List.of(audioGoal, reconstructedGoal));

        assertEquals(1, canonical.size());
        assertEquals(2, canonical.getFirst().sourceCandidateIds().size());
    }

    @Test
    void neverMergesDifferentEventTypesAtTheSameTime() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent goal = detected(mediaAssetId, FootballEventType.GOAL, 100_000,
                "Goal and yellow card for the player.");
        CandidateEvent card = detected(mediaAssetId, FootballEventType.YELLOW_CARD, 100_000,
                "Goal and yellow card for the player.");

        List<CandidateEvent> canonical = clusterer.cluster(List.of(goal, card));

        assertEquals(2, canonical.size());
        assertFalse(canonical.get(0).eventType() == canonical.get(1).eventType());
    }

    private static CandidateEvent detected(UUID mediaAssetId, FootballEventType type, long triggerMs,
                                           String context) {
        return CandidateEvent.detected(mediaAssetId, triggerMs - 15_000, triggerMs + 15_000, triggerMs,
                type, 0.84, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD, type,
                                0.84, triggerMs, "Football phrase match: " + type.name().toLowerCase()),
                        new CandidateSignal(signalType(type), type, 0.84, triggerMs, "Event-specific evidence")),
                context, NOW);
    }

    private static CandidateSignalType signalType(FootballEventType type) {
        return switch (type) {
            case GOAL -> CandidateSignalType.TRANSCRIPT_GOAL;
            case SHOT -> CandidateSignalType.TRANSCRIPT_SHOT;
            case YELLOW_CARD, RED_CARD -> CandidateSignalType.TRANSCRIPT_CARD;
            default -> CandidateSignalType.TRANSCRIPT_EVENT;
        };
    }

    private static CandidateEvent reconstructedGoal(UUID mediaAssetId, long triggerMs, long actionMs) {
        long startTimeMs = Math.min(triggerMs - 20_000, actionMs - 5_000);
        return CandidateEvent.detected(mediaAssetId, startTimeMs, triggerMs + 15_000, triggerMs,
                FootballEventType.GOAL, 0.88, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.88, triggerMs, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                                0.82, actionMs, "Action reconstructed from shot cue")),
                "Raphinha scores for Barcelona after the cross into the box.", NOW);
    }

    private static CandidateEvent scoredGoal(UUID mediaAssetId, long triggerMs, long transitionMs,
                                             String transitionEvidence, String context) {
        return CandidateEvent.detected(mediaAssetId, triggerMs - 10_000, transitionMs + 10_000,
                triggerMs, FootballEventType.GOAL, 0.88, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.88, triggerMs, "Scoring commentary"),
                        new CandidateSignal(CandidateSignalType.SCORE_STATE_TRANSITION, null,
                                0.90, transitionMs, transitionEvidence)),
                context, NOW);
    }

    private static CandidateEvent replay(UUID mediaAssetId, long triggerMs, String context) {
        return CandidateEvent.rejected(mediaAssetId, triggerMs - 15_000, triggerMs + 15_000, triggerMs,
                FootballEventType.GOAL, 0.74, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                                0.88, triggerMs, "Strong goal call in replay"),
                        new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                                0.90, triggerMs - 1_000, "Explicit replay introduction"),
                        new CandidateSignal(CandidateSignalType.REJECTION_REASON, FootballEventType.GOAL,
                                0.90, triggerMs, "REPLAY_OR_RETROSPECTIVE_CONTEXT")),
                context, NOW);
    }
}
