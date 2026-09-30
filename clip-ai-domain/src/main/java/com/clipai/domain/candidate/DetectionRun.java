package com.clipai.domain.candidate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DetectionRun(UUID id, UUID mediaAssetId, Instant createdAt, Instant startedAt,
                           Instant completedAt, DetectionRunStatus status, String detectorVersion,
                           String configurationHash, int candidateCount, int observationCount,
                           int detectedCount, int rejectedCount, String failureReason) {
    public DetectionRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mediaAssetId, "mediaAssetId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        if (detectorVersion == null || detectorVersion.isBlank()) {
            throw new IllegalArgumentException("detectorVersion must not be blank");
        }
        if (configurationHash == null || configurationHash.isBlank()) {
            throw new IllegalArgumentException("configurationHash must not be blank");
        }
        if (candidateCount < 0 || observationCount < 0 || detectedCount < 0 || rejectedCount < 0
                || detectedCount + rejectedCount > candidateCount) {
            throw new IllegalArgumentException("detection run counters are invalid");
        }
        switch (status) {
            case PENDING -> {
                if (startedAt != null || completedAt != null || failureReason != null) {
                    throw new IllegalArgumentException("pending runs cannot have lifecycle results");
                }
            }
            case RUNNING -> {
                if (startedAt == null || completedAt != null || failureReason != null) {
                    throw new IllegalArgumentException("running runs require a start time only");
                }
            }
            case COMPLETED -> {
                if (startedAt == null || completedAt == null || failureReason != null) {
                    throw new IllegalArgumentException("completed runs require start and completion times");
                }
            }
            case FAILED -> {
                if (completedAt == null || failureReason == null || failureReason.isBlank()) {
                    throw new IllegalArgumentException("failed runs require a reason and completion time");
                }
            }
        }
        if (startedAt != null && startedAt.isBefore(createdAt)
                || completedAt != null && completedAt.isBefore(createdAt)
                || completedAt != null && startedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("detection run timestamps are out of order");
        }
    }

    public static DetectionRun pending(UUID mediaAssetId, Instant createdAt,
                                       String detectorVersion, String configurationHash) {
        return new DetectionRun(UUID.randomUUID(), mediaAssetId, createdAt, null, null,
                DetectionRunStatus.PENDING, detectorVersion, configurationHash, 0, 0, 0, 0, null);
    }

    public DetectionRun start(Instant at) {
        if (status != DetectionRunStatus.PENDING) {
            throw new IllegalStateException("only pending detection runs can start");
        }
        return new DetectionRun(id, mediaAssetId, createdAt, at, null, DetectionRunStatus.RUNNING,
                detectorVersion, configurationHash, 0, 0, 0, 0, null);
    }

    public DetectionRun complete(Instant at, int candidates, int observations, int detected, int rejected) {
        if (status != DetectionRunStatus.RUNNING) {
            throw new IllegalStateException("only running detection runs can complete");
        }
        return new DetectionRun(id, mediaAssetId, createdAt, startedAt, at, DetectionRunStatus.COMPLETED,
                detectorVersion, configurationHash, candidates, observations, detected, rejected, null);
    }

    public DetectionRun fail(Instant at, String reason) {
        if (status != DetectionRunStatus.PENDING && status != DetectionRunStatus.RUNNING) {
            throw new IllegalStateException("only active detection runs can fail");
        }
        return new DetectionRun(id, mediaAssetId, createdAt, startedAt, at, DetectionRunStatus.FAILED,
                detectorVersion, configurationHash, candidateCount, observationCount,
                detectedCount, rejectedCount, reason);
    }
}
