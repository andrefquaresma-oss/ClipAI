package com.clipai.application.media;

import java.time.Instant;
import java.util.UUID;

public record ProcessingStageRun(UUID mediaAssetId, ProcessingStage stage,
                                 ProcessingStageStatus status, Integer progress,
                                 String message, Instant updatedAt) {
    public ProcessingStageRun {
        if (mediaAssetId == null || stage == null || status == null || updatedAt == null) {
            throw new IllegalArgumentException("mediaAssetId, stage, status, and updatedAt are required");
        }
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        if (message != null && message.length() > 1000) {
            throw new IllegalArgumentException("message must be at most 1000 characters");
        }
    }
}
