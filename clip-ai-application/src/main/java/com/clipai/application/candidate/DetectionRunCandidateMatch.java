package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;

public record DetectionRunCandidateMatch(CandidateEvent left, CandidateEvent right,
                                         String matchingReason, boolean scoreChanged,
                                         boolean eventTypeChanged, boolean systemStatusChanged) {
}
