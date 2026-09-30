package com.clipai.api.media;

import com.clipai.application.media.MediaProcessingStatus;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.TranscriptStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MediaProcessingStatusResponse(UUID mediaAssetId, MediaAssetStatus mediaStatus,
                                           TranscriptStatus transcriptStatus,
                                           CandidateDetectionStatus candidateDetectionStatus,
                                           List<MediaProcessingStageResponse> stages,
                                           String failureReason, Instant updatedAt) {
    static MediaProcessingStatusResponse from(MediaProcessingStatus status) {
        return new MediaProcessingStatusResponse(status.mediaAssetId(), status.mediaStatus(),
                status.transcriptStatus(), status.candidateDetectionStatus(),
                status.stages().stream().map(MediaProcessingStageResponse::from).toList(),
                status.failureReason(), status.updatedAt());
    }
}
