package com.clipai.application.matchcontext;

import java.util.List;
import java.util.UUID;

public interface MatchContextRepository {
    List<MatchStructureMarker> findStructureMarkers(UUID mediaAssetId);

    MatchStructureMarker saveStructureMarker(MatchStructureMarker marker);

    void deleteStructureMarker(UUID mediaAssetId, MatchStructureMarkerType type);

    List<MatchScoreTransition> findScoreTransitions(UUID mediaAssetId);

    MatchScoreTransition saveScoreTransition(MatchScoreTransition transition);

    void deleteScoreTransition(UUID mediaAssetId, UUID transitionId);
}
