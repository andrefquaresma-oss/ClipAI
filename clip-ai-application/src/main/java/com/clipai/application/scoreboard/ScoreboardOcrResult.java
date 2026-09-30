package com.clipai.application.scoreboard;

import com.clipai.domain.scoreboard.ScoreboardObservation;

import java.util.List;

public record ScoreboardOcrResult(String modelVersion, int sampledFrameCount, int ocrCallCount,
                                  int rawObservationCount, int scoreStateCount,
                                  int scoreTransitionCount, int scoreReversalCount,
                                  int processingErrorCount, long processingDurationMs,
                                  List<ScoreboardObservation> observations) {
    public ScoreboardOcrResult {
        observations = List.copyOf(observations);
    }
}
