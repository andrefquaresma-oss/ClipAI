package com.clipai.api.scoreboard;

import com.clipai.domain.scoreboard.ScoreboardAnalysis;
import com.clipai.domain.scoreboard.ScoreboardAnalysisStatus;

import java.time.Instant;
import java.util.UUID;

public record ScoreboardAnalysisResponse(UUID id, UUID mediaAssetId, ScoreboardAnalysisStatus status,
                                         Instant createdAt, Instant startedAt, Instant completedAt,
                                         String ocrModel, int sampledFrameCount, int ocrCallCount,
                                         int rawObservationCount, int scoreStateCount,
                                         int scoreTransitionCount, int scoreReversalCount,
                                         int processingErrorCount, long processingDurationMs, String failureReason) {
    static ScoreboardAnalysisResponse from(ScoreboardAnalysis analysis) {
        return new ScoreboardAnalysisResponse(analysis.id(), analysis.mediaAssetId(), analysis.status(),
                analysis.createdAt(), analysis.startedAt(), analysis.completedAt(), analysis.ocrModel(),
                analysis.sampledFrameCount(), analysis.ocrCallCount(), analysis.rawObservationCount(),
                analysis.scoreStateCount(), analysis.scoreTransitionCount(), analysis.scoreReversalCount(),
                analysis.processingErrorCount(), analysis.processingDurationMs(), analysis.failureReason());
    }
}
