package com.clipai.application.scoreboard;

import java.util.UUID;

public final class ScoreboardAnalysisNotFoundException extends RuntimeException {
    public ScoreboardAnalysisNotFoundException(UUID mediaAssetId, UUID analysisId) {
        super("Scoreboard analysis " + analysisId + " was not found for media asset " + mediaAssetId);
    }
}
