package com.clipai.domain.scoreboard;

import java.util.Objects;
import java.util.UUID;

public record ScoreboardObservation(UUID id, UUID analysisId, long timestampMs, String kind,
                                   String rawText, Double confidence, Integer homeScore,
                                   Integer awayScore, Integer previousHomeScore,
                                   Integer previousAwayScore, String detailsJson) {
    public ScoreboardObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(analysisId, "analysisId");
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must not be negative");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        rawText = rawText == null ? "" : rawText;
        detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
    }
}
