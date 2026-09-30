package com.clipai.application.scoreboard;

import com.clipai.domain.scoreboard.ScoreboardAnalysis;
import com.clipai.domain.scoreboard.ScoreboardObservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoreboardAnalysisRepository {
    void create(ScoreboardAnalysis analysis);

    boolean markRunning(UUID analysisId, Instant startedAt);

    void complete(ScoreboardAnalysis analysis, List<ScoreboardObservation> observations);

    void fail(UUID analysisId, Instant completedAt, String failureReason);

    Optional<ScoreboardAnalysis> find(UUID mediaAssetId, UUID analysisId);

    Optional<ScoreboardAnalysis> findById(UUID analysisId);

    List<ScoreboardAnalysis> findByMediaAssetId(UUID mediaAssetId);

    List<ScoreboardObservation> observations(UUID mediaAssetId, UUID analysisId,
                                             long startTimeMs, long endTimeMs, int limit);
}
