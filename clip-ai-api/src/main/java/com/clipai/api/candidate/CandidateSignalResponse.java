package com.clipai.api.candidate;

import com.clipai.domain.candidate.CandidateSignal;

public record CandidateSignalResponse(String type, String eventType, double confidence,
                                     long timestampMs, String evidence) {
    static CandidateSignalResponse from(CandidateSignal signal) {
        return new CandidateSignalResponse(signal.type().name(),
                signal.eventType() == null ? null : signal.eventType().name(),
                signal.confidence(), signal.timestampMs(), signal.evidence());
    }
}
