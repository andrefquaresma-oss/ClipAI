package com.clipai.domain.candidate;

import java.util.Objects;

public record CandidateSignal(CandidateSignalType type, FootballEventType eventType,
                              double confidence, long timestampMs, String evidence) {
    public CandidateSignal {
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
        evidence = evidence.trim();
    }
}
