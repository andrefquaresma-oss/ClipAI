package com.clipai.api.media;

import com.clipai.application.media.ProcessingStage;
import com.clipai.application.media.ProcessingStageRun;
import com.clipai.application.media.ProcessingStageStatus;

import java.time.Instant;

public record MediaProcessingStageResponse(ProcessingStage stage, ProcessingStageStatus status,
                                           Integer progress, String message, Instant updatedAt) {
    static MediaProcessingStageResponse from(ProcessingStageRun run) {
        return new MediaProcessingStageResponse(run.stage(), run.status(), run.progress(),
                run.message(), run.updatedAt());
    }
}
