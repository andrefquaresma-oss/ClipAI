package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;

public record CandidateReviewResult(CandidateEvent candidate, CandidateReview review,
                                    long effectiveStartTimeMs, long effectiveEndTimeMs,
                                    long maximumClipDurationMs) {
}
