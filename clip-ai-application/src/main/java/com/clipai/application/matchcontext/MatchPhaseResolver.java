package com.clipai.application.matchcontext;

import java.util.Comparator;
import java.util.List;

public final class MatchPhaseResolver {
    private MatchPhaseResolver() {
    }

    public static MatchPhase at(List<MatchStructureMarker> markers, long timestampMs) {
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
        MatchPhase phase = MatchPhase.UNKNOWN;
        for (MatchStructureMarker marker : markers.stream()
                .filter(value -> value.timestampMs() <= timestampMs)
                .sorted(Comparator.comparingLong(MatchStructureMarker::timestampMs)
                        .thenComparing(value -> value.type().ordinal()))
                .toList()) {
            phase = switch (marker.type()) {
                case BROADCAST_START, PRE_MATCH -> MatchPhase.PRE_MATCH;
                case KICKOFF, FIRST_HALF_START -> MatchPhase.FIRST_HALF;
                case HALF_TIME -> MatchPhase.HALF_TIME;
                case SECOND_HALF_START -> MatchPhase.SECOND_HALF;
                case FULL_TIME, POST_MATCH, BROADCAST_END -> MatchPhase.POST_MATCH;
            };
        }
        return phase;
    }
}
