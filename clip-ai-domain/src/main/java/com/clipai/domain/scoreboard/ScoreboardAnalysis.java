package com.clipai.domain.scoreboard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScoreboardAnalysis(UUID id, UUID mediaAssetId, ScoreboardAnalysisStatus status,
                                 Instant createdAt, Instant startedAt, Instant completedAt,
                                 String ocrModel, int sampledFrameCount, int ocrCallCount,
                                 int rawObservationCount, int scoreStateCount,
                                 int scoreTransitionCount, int scoreReversalCount,
                                 int processingErrorCount, long processingDurationMs, String failureReason) {
    public ScoreboardAnalysis {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mediaAssetId, "mediaAssetId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(ocrModel, "ocrModel");
        if (sampledFrameCount < 0 || ocrCallCount < 0 || rawObservationCount < 0
                || scoreStateCount < 0 || scoreTransitionCount < 0 || scoreReversalCount < 0
                || processingErrorCount < 0 || processingDurationMs < 0) {
            throw new IllegalArgumentException("analysis counters must not be negative");
        }
    }
}
