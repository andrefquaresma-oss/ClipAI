package com.clipai.application.matchcontext;

import java.util.List;

public record MatchContext(List<MatchStructureMarker> structureMarkers,
                           List<MatchScoreTransition> scoreTransitions) {
    public MatchContext {
        structureMarkers = List.copyOf(structureMarkers);
        scoreTransitions = List.copyOf(scoreTransitions);
    }
}
