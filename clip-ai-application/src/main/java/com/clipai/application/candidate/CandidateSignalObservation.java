package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignal;

import java.util.List;

public record CandidateSignalObservation(long timestampMs, List<CandidateSignal> signals) {
    public CandidateSignalObservation {
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
        signals = List.copyOf(signals);
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("signals must not be empty");
        }
    }
}
