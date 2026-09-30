package com.clipai.api.groundtruth;

import com.clipai.application.groundtruth.GroundTruthEvent;
import com.clipai.domain.candidate.FootballEventType;

import java.time.Instant;
import java.util.UUID;

public record GroundTruthEventResponse(UUID id, UUID mediaAssetId, UUID sourceCandidateId,
                                       FootballEventType eventType, long timestampMs, long startTimeMs,
                                       long endTimeMs, String note, Instant createdAt, Instant updatedAt) {
    static GroundTruthEventResponse from(GroundTruthEvent event) {
        return new GroundTruthEventResponse(event.id(), event.mediaAssetId(), event.sourceCandidateId(),
                event.eventType(), event.timestampMs(), event.startTimeMs(), event.endTimeMs(),
                event.note(), event.createdAt(), event.updatedAt());
    }
}
