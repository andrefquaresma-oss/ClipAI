package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.candidate.CandidateEvent;

import java.util.List;
import java.util.UUID;

public record CandidateEventList(UUID mediaAssetId, CandidateDetectionStatus detectionStatus,
                                 String failureReason, List<CandidateEvent> events) {
    public CandidateEventList {
        events = List.copyOf(events);
    }
}
