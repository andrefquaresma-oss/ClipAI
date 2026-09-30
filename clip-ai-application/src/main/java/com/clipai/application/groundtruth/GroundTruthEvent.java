package com.clipai.application.groundtruth;

import com.clipai.domain.candidate.FootballEventType;

import java.time.Instant;
import java.util.UUID;

public record GroundTruthEvent(UUID id, UUID mediaAssetId, UUID sourceCandidateId,
                               FootballEventType eventType, long timestampMs, long startTimeMs,
                               long endTimeMs, String note, Instant createdAt, Instant updatedAt) {
    public GroundTruthEvent {
        if (id == null || mediaAssetId == null || eventType == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("id, mediaAssetId, eventType, and timestamps are required");
        }
        if (timestampMs < startTimeMs || startTimeMs < 0 || endTimeMs <= startTimeMs) {
            throw new IllegalArgumentException("ground truth timestamps are invalid");
        }
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("note must be at most 2000 characters");
        }
    }
}
