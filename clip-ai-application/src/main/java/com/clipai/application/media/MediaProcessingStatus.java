package com.clipai.application.media;

import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.transcript.TranscriptStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MediaProcessingStatus(UUID mediaAssetId, MediaAssetStatus mediaStatus,
                                    TranscriptStatus transcriptStatus,
                                    CandidateDetectionStatus candidateDetectionStatus,
                                    List<ProcessingStageRun> stages, String failureReason,
                                    Instant updatedAt) {
    public MediaProcessingStatus {
        stages = List.copyOf(stages);
    }
}
