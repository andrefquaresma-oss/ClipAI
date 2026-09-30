package com.clipai.application.candidate;

public record CandidateEventClusteringSettings(long goalTriggerWindowMs,
                                               long cardTriggerWindowMs,
                                               long shotTriggerWindowMs,
                                               long otherTriggerWindowMs,
                                               double minimumContextSimilarity,
                                               long maximumEventDurationMs) {
    public CandidateEventClusteringSettings {
        if (goalTriggerWindowMs < 0 || cardTriggerWindowMs < 0 || shotTriggerWindowMs < 0
                || otherTriggerWindowMs < 0 || maximumEventDurationMs <= 0) {
            throw new IllegalArgumentException("event clustering windows must be non-negative");
        }
        if (!Double.isFinite(minimumContextSimilarity)
                || minimumContextSimilarity < 0 || minimumContextSimilarity > 1) {
            throw new IllegalArgumentException("minimumContextSimilarity must be between zero and one");
        }
    }

    public static CandidateEventClusteringSettings defaults() {
        return new CandidateEventClusteringSettings(15_000, 20_000, 3_000, 10_000, 0.48, 60_000);
    }
}
