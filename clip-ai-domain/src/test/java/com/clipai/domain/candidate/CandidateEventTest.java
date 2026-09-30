package com.clipai.domain.candidate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateEventTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsInvalidWindowsAndEmptyEvidence() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                FootballEventType.GOAL, 0.9, 1000, "Football phrase match");

        assertThrows(IllegalArgumentException.class, () -> CandidateEvent.detected(mediaAssetId,
                1000, 1000, 1000, FootballEventType.GOAL, 0.9, List.of(signal), null, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> CandidateEvent.detected(mediaAssetId,
                0, 2000, 1000, FootballEventType.GOAL, 0.9, List.of(), null, Instant.now()));
    }

    @Test
    void acceptsTriggerAtEitherInclusiveWindowBoundaryAndInside() {
        UUID mediaAssetId = UUID.randomUUID();

        assertDoesNotThrow(() -> event(mediaAssetId, 1_000, 2_000, 1_000));
        assertDoesNotThrow(() -> event(mediaAssetId, 1_000, 2_000, 1_500));
        assertDoesNotThrow(() -> event(mediaAssetId, 1_000, 2_000, 2_000));
    }

    @Test
    void rejectsTriggerBeforeOrAfterTheCandidateWindow() {
        UUID mediaAssetId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> event(mediaAssetId, 1_000, 2_000, 999));
        assertThrows(IllegalArgumentException.class,
                () -> event(mediaAssetId, 1_000, 2_000, 2_001));
    }

    private static CandidateEvent event(UUID mediaAssetId, long startTimeMs,
                                        long endTimeMs, long triggerTimestampMs) {
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                FootballEventType.GOAL, 0.9, triggerTimestampMs, "Candidate trigger");
        return CandidateEvent.detected(mediaAssetId, startTimeMs, endTimeMs,
                triggerTimestampMs, FootballEventType.GOAL, 0.9, List.of(signal), null, NOW);
    }
}
