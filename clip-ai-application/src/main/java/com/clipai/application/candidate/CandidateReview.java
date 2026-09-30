package com.clipai.application.candidate;

import java.time.Instant;
import java.util.UUID;

public record CandidateReview(UUID candidateId, CandidateReviewStatus status,
                              Long manualStartTimeMs, Long manualEndTimeMs, String note,
                              com.clipai.domain.candidate.FootballEventType eventTypeOverride,
                              HumanRejectionReason humanRejectionReason,
                              Instant updatedAt) {
    public CandidateReview(UUID candidateId, CandidateReviewStatus status,
                           Long manualStartTimeMs, Long manualEndTimeMs, Instant updatedAt) {
        this(candidateId, status, manualStartTimeMs, manualEndTimeMs, null, null, null, updatedAt);
    }

    public CandidateReview(UUID candidateId, CandidateReviewStatus status,
                           Long manualStartTimeMs, Long manualEndTimeMs, String note,
                           com.clipai.domain.candidate.FootballEventType eventTypeOverride,
                           Instant updatedAt) {
        this(candidateId, status, manualStartTimeMs, manualEndTimeMs, note,
                eventTypeOverride, null, updatedAt);
    }

    public CandidateReview {
        if (candidateId == null || status == null || updatedAt == null) {
            throw new IllegalArgumentException("candidateId, status, and updatedAt are required");
        }
        if ((manualStartTimeMs == null) != (manualEndTimeMs == null)) {
            throw new IllegalArgumentException("manual start and end must be provided together");
        }
        if (manualStartTimeMs != null
                && (manualStartTimeMs < 0 || manualEndTimeMs <= manualStartTimeMs)) {
            throw new IllegalArgumentException("manual boundary must be non-negative and end after start");
        }
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("note must be at most 2000 characters");
        }
    }

    public boolean hasManualBoundary() {
        return manualStartTimeMs != null;
    }
}
