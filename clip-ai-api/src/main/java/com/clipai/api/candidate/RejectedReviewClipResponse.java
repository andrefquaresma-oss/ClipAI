package com.clipai.api.candidate;

import com.clipai.application.candidate.RejectedReviewClipResult;

import java.util.UUID;

public record RejectedReviewClipResponse(UUID candidateId, String status,
                                         long startTimeMs, long endTimeMs, long triggerTimestampMs,
                                         String videoUrl, String failureReason) {
    static RejectedReviewClipResponse from(UUID candidateId, RejectedReviewClipResult result,
                                           String videoUrl) {
        return new RejectedReviewClipResponse(candidateId, result.status().name(),
                result.startTimeMs(), result.endTimeMs(), result.triggerTimestampMs(),
                videoUrl, result.failureReason());
    }
}
