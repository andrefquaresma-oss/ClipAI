package com.clipai.api.scoreboard;

import com.clipai.domain.scoreboard.ScoreboardObservation;

import java.util.UUID;

public record ScoreboardObservationResponse(UUID id, UUID analysisId, long timestampMs, String kind,
                                            String rawText, Double confidence, Integer homeScore,
                                            Integer awayScore, Integer previousHomeScore,
                                            Integer previousAwayScore, String detailsJson) {
    static ScoreboardObservationResponse from(ScoreboardObservation observation) {
        return new ScoreboardObservationResponse(observation.id(), observation.analysisId(),
                observation.timestampMs(), observation.kind(), observation.rawText(),
                observation.confidence(), observation.homeScore(), observation.awayScore(),
                observation.previousHomeScore(), observation.previousAwayScore(),
                observation.detailsJson());
    }
}
