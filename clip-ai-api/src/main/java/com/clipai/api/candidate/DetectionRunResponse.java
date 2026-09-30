package com.clipai.api.candidate;

import com.clipai.application.candidate.DetectionRunSummary;
import com.clipai.domain.candidate.DetectionRunStatus;

import java.time.Instant;
import java.util.UUID;

public record DetectionRunResponse(String runId, UUID mediaAssetId, boolean legacy,
                                   DetectionRunStatus status, String detectorVersion,
                                   String configurationHash, Instant createdAt, Instant startedAt,
                                   Instant completedAt, int candidateCount, int observationCount,
                                   int detectedCount, int rejectedCount, String failureReason) {
    static DetectionRunResponse from(DetectionRunSummary summary) {
        return new DetectionRunResponse(summary.runId(), summary.mediaAssetId(), summary.legacy(),
                summary.status(), summary.detectorVersion(), summary.configurationHash(),
                summary.createdAt(), summary.startedAt(), summary.completedAt(),
                summary.candidateCount(), summary.observationCount(), summary.detectedCount(),
                summary.rejectedCount(), summary.failureReason());
    }
}
