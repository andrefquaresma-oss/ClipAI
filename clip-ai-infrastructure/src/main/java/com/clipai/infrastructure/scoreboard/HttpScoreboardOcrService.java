package com.clipai.infrastructure.scoreboard;

import com.clipai.application.scoreboard.ScoreboardOcrResult;
import com.clipai.application.scoreboard.ScoreboardOcrService;
import com.clipai.domain.scoreboard.ScoreboardObservation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Component
public class HttpScoreboardOcrService implements ScoreboardOcrService {
    private final RestClient client;

    public HttpScoreboardOcrService(@Qualifier("scoreboardOcrRestClient") RestClient scoreboardOcrRestClient) {
        this.client = scoreboardOcrRestClient;
    }

    @Override
    public ScoreboardOcrResult analyze(Path videoPath, UUID analysisId) {
        WorkerScoreboardResponse response = client.post()
                .uri("/scoreboards/analyze")
                .body(new WorkerScoreboardRequest(videoPath.toString()))
                .retrieve()
                .body(WorkerScoreboardResponse.class);
        if (response == null) {
            throw new IllegalStateException("Scoreboard OCR worker returned an empty response");
        }
        List<ScoreboardObservation> observations = response.observations().stream()
                .map(observation -> new ScoreboardObservation(UUID.randomUUID(), analysisId,
                        observation.timestampMs(), observation.kind(), observation.rawText(),
                        observation.confidence(), observation.homeScore(), observation.awayScore(),
                        observation.previousHomeScore(), observation.previousAwayScore(),
                        observation.detailsJson()))
                .toList();
        return new ScoreboardOcrResult(response.modelVersion(),
                response.sampledFrameCount(), response.ocrCallCount(), response.rawObservationCount(),
                response.scoreStateCount(), response.scoreTransitionCount(), response.scoreReversalCount(),
                response.processingErrorCount(), response.processingDurationMs(), observations);
    }

    private record WorkerScoreboardRequest(String videoPath) {
    }

    private record WorkerScoreboardResponse(String modelVersion, int sampledFrameCount, int ocrCallCount,
                                            int rawObservationCount, int scoreStateCount,
                                            int scoreTransitionCount, int scoreReversalCount,
                                            int processingErrorCount,
                                            long processingDurationMs,
                                            List<WorkerScoreboardObservation> observations) {
    }

    private record WorkerScoreboardObservation(long timestampMs, String kind, String rawText,
                                               Double confidence, Integer homeScore, Integer awayScore,
                                               Integer previousHomeScore, Integer previousAwayScore,
                                               String detailsJson) {
    }
}
