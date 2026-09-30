package com.clipai.domain.candidate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CandidateObservation(UUID id, UUID mediaAssetId, UUID detectionRunId,
                                   CandidateSignal signal, Instant observedAt) {
    public CandidateObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mediaAssetId, "mediaAssetId");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public CandidateObservation(UUID id, UUID mediaAssetId, CandidateSignal signal, Instant observedAt) {
        this(id, mediaAssetId, null, signal, observedAt);
    }
}
