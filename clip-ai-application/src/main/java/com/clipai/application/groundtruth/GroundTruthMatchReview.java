package com.clipai.application.groundtruth;

import java.time.Instant;
import java.util.UUID;

public record GroundTruthMatchReview(UUID mediaAssetId, GroundTruthReviewStatus status, Instant updatedAt) {
    public GroundTruthMatchReview {
        if (mediaAssetId == null || status == null || updatedAt == null) {
            throw new IllegalArgumentException("mediaAssetId, status, and updatedAt are required");
        }
    }
}
