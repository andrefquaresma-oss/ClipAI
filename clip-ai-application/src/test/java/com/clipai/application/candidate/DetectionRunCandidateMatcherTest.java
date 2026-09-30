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

class DetectionRunCandidateMatcherTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ASSET_ID = UUID.randomUUID();
    private final DetectionRunCandidateMatcher matcher = new DetectionRunCandidateMatcher();

    @Test
    void matchesNearestSameTypeOneToOneAndReportsChanges() {
        CandidateEvent left = event(60_000, FootballEventType.GOAL, 0.82,
                CandidateEventStatus.DETECTED, "gol jogador finalizou");
        CandidateEvent rightNear = event(65_000, FootballEventType.GOAL, 0.71,
                CandidateEventStatus.REJECTED, "gol jogador finalizou");
        CandidateEvent rightFar = event(80_001, FootballEventType.GOAL, 0.90,
                CandidateEventStatus.DETECTED, "gol jogador finalizou");

        DetectionRunCandidateMatcher.Result result = matcher.match(
                List.of(left), List.of(rightFar, rightNear));

        assertEquals(1, result.matches().size());
        assertEquals(rightNear.id(), result.matches().getFirst().right().id());
        assertTrue(result.matches().getFirst().scoreChanged());
        assertTrue(result.matches().getFirst().systemStatusChanged());
        assertEquals(List.of(rightFar), result.onlyRight());
        assertTrue(result.onlyLeft().isEmpty());
    }

    @Test
    void onlyMatchesDifferentTypesWhenContextIsSufficientlySimilar() {
        CandidateEvent left = event(20_000, FootballEventType.SHOT, 0.7,
                CandidateEventStatus.DETECTED, "attacker shoots toward goal");
        CandidateEvent similar = event(22_000, FootballEventType.GOAL, 0.8,
                CandidateEventStatus.DETECTED, "attacker shoots toward goal");
        CandidateEvent unrelated = event(23_000, FootballEventType.RED_CARD, 0.8,
                CandidateEventStatus.DETECTED, "referee books defender");

        DetectionRunCandidateMatcher.Result result = matcher.match(
                List.of(left), List.of(unrelated, similar));

        assertEquals(similar.id(), result.matches().getFirst().right().id());
        assertTrue(result.matches().getFirst().eventTypeChanged());
        assertEquals(List.of(unrelated), result.onlyRight());
    }

    @Test
    void doesNotMatchSameTypeEventsOutsideTemporalWindow() {
        CandidateEvent left = event(1_000, FootballEventType.GOAL, 0.8,
                CandidateEventStatus.DETECTED, "goal scored");
        CandidateEvent right = event(16_001, FootballEventType.GOAL, 0.8,
                CandidateEventStatus.DETECTED, "goal scored");

        DetectionRunCandidateMatcher.Result result = matcher.match(List.of(left), List.of(right));

        assertTrue(result.matches().isEmpty());
        assertEquals(List.of(left), result.onlyLeft());
        assertEquals(List.of(right), result.onlyRight());
        assertFalse(result.matches().stream().anyMatch(match -> match.eventTypeChanged()));
    }

    private static CandidateEvent event(long timestampMs, FootballEventType type, double score,
                                        CandidateEventStatus status, String context) {
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_EVENT,
                type, 0.9, timestampMs, "test evidence");
        return status == CandidateEventStatus.DETECTED
                ? CandidateEvent.detected(ASSET_ID, timestampMs, timestampMs + 1_000,
                        timestampMs, type, score, List.of(signal), context, NOW)
                : CandidateEvent.rejected(ASSET_ID, timestampMs, timestampMs + 1_000,
                        timestampMs, type, score, List.of(signal), context, NOW);
    }
}
