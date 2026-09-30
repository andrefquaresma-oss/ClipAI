package com.clipai.application.matchcontext;

import java.time.Instant;
import java.util.UUID;

public record MatchScoreTransition(UUID id, UUID mediaAssetId, long timestampMs,
                                   int homeScore, int awayScore, ScoreTransitionSource source,
                                   Double confidence, Instant createdAt, Instant updatedAt) {
}
