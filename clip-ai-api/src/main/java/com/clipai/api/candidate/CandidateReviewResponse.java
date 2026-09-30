package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateReviewResult;
import com.clipai.application.candidate.CandidateReviewStatus;
import com.clipai.application.candidate.HumanRejectionReason;
import com.clipai.domain.candidate.FootballEventType;

import java.time.Instant;
import java.util.UUID;

public record CandidateReviewResponse(UUID candidateId, String detectionStatus,
                                      CandidateReviewStatus reviewStatus,
                                      long automaticStartTimeMs, long automaticEndTimeMs,
                                      Long manualStartTimeMs, Long manualEndTimeMs,
                                      String note, FootballEventType eventTypeOverride,
                                      HumanRejectionReason humanRejectionReason,
                                      long effectiveStartTimeMs, long effectiveEndTimeMs,
                                      long maximumClipDurationMs, boolean clipGenerated,
                                      Boolean reviewClipCleanupFailed, Instant updatedAt) {
    static CandidateReviewResponse from(CandidateReviewResult result, boolean clipGenerated) {
        return from(result, clipGenerated, null);
    }

    static CandidateReviewResponse from(CandidateReviewResult result, boolean clipGenerated,
                                        Boolean reviewClipCleanupFailed) {
        return new CandidateReviewResponse(result.candidate().id(), result.candidate().status().name(),
                result.review().status(), result.candidate().startTimeMs(), result.candidate().endTimeMs(),
                result.review().manualStartTimeMs(), result.review().manualEndTimeMs(),
                result.review().note(), result.review().eventTypeOverride(),
                result.review().humanRejectionReason(),
                result.effectiveStartTimeMs(), result.effectiveEndTimeMs(),
                result.maximumClipDurationMs(), clipGenerated, reviewClipCleanupFailed,
                result.review().updatedAt());
    }
}
