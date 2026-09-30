package com.clipai.application.matchcontext;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchPhaseResolverTest {
    @Test
    void derivesPhaseFromMarkersWithoutTreatingMediaStartAsKickoff() {
        UUID assetId = UUID.randomUUID();
        List<MatchStructureMarker> markers = List.of(
                marker(assetId, MatchStructureMarkerType.KICKOFF, 12_000),
                marker(assetId, MatchStructureMarkerType.HALF_TIME, 2_700_000),
                marker(assetId, MatchStructureMarkerType.SECOND_HALF_START, 3_000_000),
                marker(assetId, MatchStructureMarkerType.FULL_TIME, 5_700_000));

        assertEquals(MatchPhase.UNKNOWN, MatchPhaseResolver.at(markers, 0));
        assertEquals(MatchPhase.PRE_MATCH, MatchPhaseResolver.at(
                List.of(marker(assetId, MatchStructureMarkerType.BROADCAST_START, 0)), 0));
        assertEquals(MatchPhase.FIRST_HALF, MatchPhaseResolver.at(markers, 12_000));
        assertEquals(MatchPhase.HALF_TIME, MatchPhaseResolver.at(markers, 2_700_000));
        assertEquals(MatchPhase.SECOND_HALF, MatchPhaseResolver.at(markers, 3_000_000));
        assertEquals(MatchPhase.POST_MATCH, MatchPhaseResolver.at(markers, 5_700_000));
    }

    private static MatchStructureMarker marker(UUID assetId, MatchStructureMarkerType type, long timestamp) {
        return new MatchStructureMarker(assetId, type, timestamp, Instant.EPOCH);
    }
}
