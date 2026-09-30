package com.clipai.application.candidate;

import com.clipai.domain.candidate.DetectionRun;
import com.clipai.domain.candidate.DetectionRunStatus;

import java.time.Instant;
import java.util.UUID;

public record DetectionRunSummary(String runId, UUID mediaAssetId, boolean legacy,
                                  DetectionRunStatus status, String detectorVersion,
                                  String configurationHash, Instant createdAt, Instant startedAt,
                                  Instant completedAt, int candidateCount, int observationCount,
                                  int detectedCount, int rejectedCount, String failureReason) {
    public static DetectionRunSummary from(DetectionRun run) {
        return new DetectionRunSummary(run.id().toString(), run.mediaAssetId(), false, run.status(),
                run.detectorVersion(), run.configurationHash(), run.createdAt(), run.startedAt(),
                run.completedAt(), run.candidateCount(), run.observationCount(),
                run.detectedCount(), run.rejectedCount(), run.failureReason());
    }

    public static DetectionRunSummary legacy(UUID mediaAssetId, int candidateCount,
                                             int observationCount, int detectedCount, int rejectedCount) {
        return new DetectionRunSummary("legacy", mediaAssetId, true, DetectionRunStatus.COMPLETED,
                "Legacy run metadata unavailable", null, null, null, null, candidateCount,
                observationCount, detectedCount, rejectedCount, null);
    }
}
