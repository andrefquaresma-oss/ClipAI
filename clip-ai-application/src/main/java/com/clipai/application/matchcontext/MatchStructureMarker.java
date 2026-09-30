package com.clipai.application.matchcontext;

import java.time.Instant;
import java.util.UUID;

public record MatchStructureMarker(UUID mediaAssetId, MatchStructureMarkerType type,
                                   long timestampMs, Instant updatedAt) {
}
