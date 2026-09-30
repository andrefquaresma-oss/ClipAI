package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateReviewStatus;
import com.clipai.application.candidate.HumanRejectionReason;
import com.clipai.domain.candidate.FootballEventType;
import jakarta.validation.constraints.NotNull;

public record CandidateReviewRequest(@NotNull CandidateReviewStatus status,
                                     Long manualStartTimeMs, Long manualEndTimeMs,
                                     String note, FootballEventType eventTypeOverride,
                                     HumanRejectionReason humanRejectionReason) {
    public CandidateReviewRequest(CandidateReviewStatus status, Long manualStartTimeMs,
                                  Long manualEndTimeMs) {
        this(status, manualStartTimeMs, manualEndTimeMs, null, null, null);
    }
}
