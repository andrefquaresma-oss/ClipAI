package com.clipai.application.candidate;

import com.clipai.domain.candidate.DetectionRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DetectionRunRepository {
    boolean createPending(DetectionRun run);

    boolean markRunning(UUID runId, Instant startedAt);

    Optional<DetectionRun> findById(UUID runId);

    List<DetectionRun> findByMediaAssetId(UUID mediaAssetId);

    void markFailed(UUID runId, Instant completedAt, String failureReason);
}
