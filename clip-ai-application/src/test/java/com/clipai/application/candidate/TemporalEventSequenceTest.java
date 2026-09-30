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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalEventSequenceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void retainsNegativeOutcomeEvidenceWithoutContradictingConfirmedGoal() {
        CandidateEvent candidate = CandidateEvent.detected(UUID.randomUUID(), 0, 15_000, 8_000,
                FootballEventType.GOAL, 0.9, List.of(
                        signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 6_000,
                                "Football phrase match: shot"),
                        signal(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null, 6_500,
                                "OUTCOME=BLOCK; transcript phrase match: blocked"),
                        signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 8_000,
                                "Scoring action reconstructed from event evidence")),
                "Shot blocked; goal confirmed after follow-up.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertEquals("CANONICAL_GOAL", sequence.assessment());
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.BLOCK && event.negativeOutcome()));
        assertTrue(sequence.negativeEvidence().getFirst().contains("BLOCK"));
        assertFalse(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.CONTRADICTS));
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.GOAL && event.timestampMs() == 8_000));
        assertEquals(List.of(6_000L, 6_500L, 8_000L, 8_000L),
                sequence.events().stream().map(event -> event.timestampMs()).toList());
    }

    @Test
    void linksNegativeOutcomeToAnAlreadyRejectedGoalHypothesis() {
        CandidateEvent candidate = CandidateEvent.rejected(UUID.randomUUID(), 0, 15_000, 8_000,
                FootballEventType.GOAL, 0.4, List.of(
                        signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 6_000,
                                "Football phrase match: shot"),
                        signal(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null, 6_500,
                                "OUTCOME=BLOCK; transcript phrase match: blocked"),
                        signal(CandidateSignalType.REJECTION_REASON, FootballEventType.GOAL, 8_000,
                                "GOAL_HYPOTHESIS_UNCONFIRMED")),
                "Shot blocked; no scoring evidence.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.CONTRADICTS));
    }

    @Test
    void linksShotOutcomeAcrossInterveningAudioEvidenceWithoutSuppressingCanonicalGoal() {
        CandidateEvent candidate = CandidateEvent.detected(UUID.randomUUID(), 0, 20_000, 10_000,
                FootballEventType.GOAL, 0.9, List.of(
                        signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 7_000,
                                "Shot"),
                        signal(CandidateSignalType.AUDIO_SPIKE, null, 7_250, "Crowd intensity rise"),
                        signal(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null, 7_500,
                                "OUTCOME=SAVE; transcript phrase match: good save"),
                        signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 10_000,
                                "Later follow-up confirms scoring action")),
                "Goal candidate retains an earlier save as contextual evidence.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertEquals("CANONICAL_GOAL", sequence.assessment());
        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.OUTCOME_OF
                        && sequence.events().get(relationship.fromOrdinal()).type() == TemporalAtomicEventType.SHOT
                        && sequence.events().get(relationship.toOrdinal()).type() == TemporalAtomicEventType.SAVE));
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.SAVE && event.negativeOutcome()));
    }

    @Test
    void keepsReplayAndRestartAtomsDistinctFromCanonicalGoalAssessmentWithoutAssociation() {
        CandidateEvent candidate = new CandidateEvent(UUID.randomUUID(), UUID.randomUUID(),
                1_000, 20_000, 10_000, FootballEventType.GOAL, 0.75, List.of(
                signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 10_000,
                        "Live goal reconstruction"),
                signal(CandidateSignalType.RESTART_CONTEXT, null, 13_000, "Restart after live event"),
                signal(CandidateSignalType.REPLAY_CONTEXT, null, 18_000, "Explicit replay introduction")),
                null, CandidateEventStatus.DETECTED, NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertEquals("CANONICAL_GOAL", sequence.assessment());
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.RESTART));
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.REPLAY_INTRODUCTION));
        assertFalse(sequence.events().stream().anyMatch(event -> event.negativeOutcome()));
        assertFalse(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.REPLAY_OF));
    }

    @Test
    void associatesPersistedReplaySupportWithCanonicalGoalAtReplayTimestamp() {
        UUID replayCandidateId = UUID.randomUUID();
        CandidateEvent candidate = CandidateEvent.detected(UUID.randomUUID(), 0, 40_000, 10_000,
                FootballEventType.GOAL, 0.9, List.of(
                        signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 10_000,
                                "Live scoring action"),
                        signal(CandidateSignalType.EVENT_ASSOCIATION, FootballEventType.GOAL, 10_000,
                                "REPLAY_OF_EXISTING_GOAL; DUPLICATE_EVENT; replay-only GOAL candidate "
                                        + replayCandidateId + " at 25000 ms was associated with canonical goal "
                                        + UUID.randomUUID() + " without extending its live clip window")),
                "Canonical live goal with replay support.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        TemporalAtomicEvent replay = sequence.events().stream()
                .filter(event -> event.type() == TemporalAtomicEventType.REPLAY_EVENT)
                .findFirst().orElseThrow();
        assertEquals(25_000, replay.timestampMs());
        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.REPLAY_OF
                        && relationship.fromOrdinal() == replay.ordinal()));
    }

    @Test
    void retainsReplayCueTimestampFromLiveGoalReconstructionEvidence() {
        CandidateEvent candidate = CandidateEvent.detected(UUID.randomUUID(), 0, 40_000, 10_000,
                FootballEventType.GOAL, 0.9, List.of(
                        signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 10_000,
                                "Live scoring action"),
                        signal(CandidateSignalType.EVENT_ASSOCIATION, FootballEventType.GOAL, 10_000,
                                "REPLAY_EVIDENCE_FOR_LIVE_EVENT; explicit replay cue at 25000 ms and "
                                        + "scoring phrase at 25600 ms corroborate this preceding live attack")),
                "Canonical live goal reconstructed with replay support.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        TemporalAtomicEvent replay = sequence.events().stream()
                .filter(event -> event.type() == TemporalAtomicEventType.REPLAY_EVENT)
                .findFirst().orElseThrow();
        assertEquals(25_000, replay.timestampMs());
        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.REPLAY_OF
                        && relationship.fromOrdinal() == replay.ordinal()));
    }

    @Test
    void relatesConnectedAttackAndShotToGoalButStopsAtAnUnrelatedAction() {
        CandidateEvent candidate = CandidateEvent.detected(UUID.randomUUID(), 0, 50_000, 30_000,
                FootballEventType.GOAL, 0.9, List.of(
                        signal(CandidateSignalType.ATTACK_BUILDUP, FootballEventType.ATTACK, 20_000,
                                "Connected attack"),
                        signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 27_000,
                                "Shot immediately before goal"),
                        signal(CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL, 30_000,
                                "Scoring action"),
                        signal(CandidateSignalType.UNRELATED_ACTION_CONTEXT, null, 22_000,
                                "Unrelated action closes earlier phase")),
                "Goal follows a connected attack and shot.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.SUPPORTS
                        && sequence.events().get(relationship.fromOrdinal()).type() == TemporalAtomicEventType.SHOT));
        assertFalse(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.SUPPORTS
                        && sequence.events().get(relationship.fromOrdinal()).type() == TemporalAtomicEventType.ATTACK));
    }

    @Test
    void linksPenaltyAwardToAttemptAndOutcomeEvidence() {
        CandidateEvent candidate = CandidateEvent.rejected(UUID.randomUUID(), 0, 30_000, 20_000,
                FootballEventType.GOAL, 0.45, List.of(
                        signal(CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.PENALTY, 10_000,
                                "Penalty awarded"),
                        signal(CandidateSignalType.AUDIO_SPIKE, null, 12_000, "Crowd intensity rise"),
                        signal(CandidateSignalType.TRANSCRIPT_PENALTY, FootballEventType.PENALTY, 15_000,
                                "Penalty attempt"),
                        signal(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null, 16_000,
                                "OUTCOME=SAVE; transcript phrase match: keeper saves")),
                "Penalty attempt saved.", NOW);

        TemporalEventSequence sequence = TemporalEventSequence.from(candidate);

        assertTrue(sequence.relationships().stream().anyMatch(relationship ->
                relationship.type() == TemporalRelationshipType.SUPPORTS
                        && sequence.events().get(relationship.fromOrdinal()).type()
                        == TemporalAtomicEventType.PENALTY_AWARDED
                        && sequence.events().get(relationship.toOrdinal()).type()
                        == TemporalAtomicEventType.PENALTY_ATTEMPT));
        assertTrue(sequence.events().stream().anyMatch(event ->
                event.type() == TemporalAtomicEventType.SAVE && event.negativeOutcome()));
    }

    private static CandidateSignal signal(CandidateSignalType type, FootballEventType eventType,
                                          long timestamp, String evidence) {
        return new CandidateSignal(type, eventType, 0.8, timestamp, evidence);
    }
}
