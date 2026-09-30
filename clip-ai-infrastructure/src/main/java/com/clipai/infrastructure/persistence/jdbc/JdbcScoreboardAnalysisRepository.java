package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.scoreboard.ScoreboardAnalysisRepository;
import com.clipai.domain.scoreboard.ScoreboardAnalysis;
import com.clipai.domain.scoreboard.ScoreboardAnalysisStatus;
import com.clipai.domain.scoreboard.ScoreboardObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcScoreboardAnalysisRepository implements ScoreboardAnalysisRepository {
    private final JdbcTemplate jdbc;

    public JdbcScoreboardAnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(ScoreboardAnalysis analysis) {
        jdbc.update("""
                INSERT INTO scoreboard_analyses (id, media_asset_id, created_at, status, ocr_model)
                VALUES (?, ?, ?, ?, ?)
                """, analysis.id(), analysis.mediaAssetId(), Timestamp.from(analysis.createdAt()),
                analysis.status().name(), analysis.ocrModel());
    }

    @Override
    @Transactional
    public boolean markRunning(UUID analysisId, java.time.Instant startedAt) {
        return jdbc.update("""
                UPDATE scoreboard_analyses SET status = 'RUNNING', started_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(startedAt), analysisId) == 1;
    }

    @Override
    @Transactional
    public void complete(ScoreboardAnalysis analysis, List<ScoreboardObservation> observations) {
        List<Object[]> rows = observations.stream().map(observation -> new Object[] {
                observation.id(), observation.analysisId(), observation.timestampMs(), observation.kind(),
                observation.rawText(), observation.confidence(), observation.homeScore(), observation.awayScore(),
                observation.previousHomeScore(), observation.previousAwayScore(), observation.detailsJson()
        }).toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO scoreboard_observations
                        (id, analysis_id, timestamp_ms, kind, raw_text, confidence, home_score, away_score,
                         previous_home_score, previous_away_score, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, rows);
        }
        int updated = jdbc.update("""
                UPDATE scoreboard_analyses
                   SET status = ?, completed_at = ?, ocr_model = ?, sampled_frame_count = ?,
                       ocr_call_count = ?, raw_observation_count = ?, score_state_count = ?,
                       score_transition_count = ?, score_reversal_count = ?, processing_error_count = ?,
                       processing_duration_ms = ?, failure_reason = NULL
                 WHERE id = ? AND media_asset_id = ? AND status = 'RUNNING'
                """, ScoreboardAnalysisStatus.COMPLETED.name(), Timestamp.from(analysis.completedAt()),
                analysis.ocrModel(), analysis.sampledFrameCount(), analysis.ocrCallCount(),
                analysis.rawObservationCount(), analysis.scoreStateCount(), analysis.scoreTransitionCount(),
                analysis.scoreReversalCount(), analysis.processingErrorCount(),
                analysis.processingDurationMs(), analysis.id(), analysis.mediaAssetId());
        if (updated != 1) {
            throw new IllegalStateException("Scoreboard analysis is not active or does not belong to the media asset");
        }
    }

    @Override
    @Transactional
    public void fail(UUID analysisId, java.time.Instant completedAt, String failureReason) {
        String reason = failureReason == null || failureReason.isBlank()
                ? "Scoreboard OCR failed" : failureReason;
        if (reason.length() > 1000) {
            reason = reason.substring(0, 1000);
        }
        jdbc.update("""
                UPDATE scoreboard_analyses
                   SET status = 'FAILED', completed_at = ?, failure_reason = ?
                 WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, Timestamp.from(completedAt), reason, analysisId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScoreboardAnalysis> find(UUID mediaAssetId, UUID analysisId) {
        return jdbc.query("""
                SELECT * FROM scoreboard_analyses WHERE media_asset_id = ? AND id = ?
                """, JdbcScoreboardAnalysisRepository::mapAnalysis, mediaAssetId, analysisId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScoreboardAnalysis> findById(UUID analysisId) {
        return jdbc.query("SELECT * FROM scoreboard_analyses WHERE id = ?",
                JdbcScoreboardAnalysisRepository::mapAnalysis, analysisId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScoreboardAnalysis> findByMediaAssetId(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT * FROM scoreboard_analyses WHERE media_asset_id = ?
                ORDER BY created_at DESC, id DESC
                """, JdbcScoreboardAnalysisRepository::mapAnalysis, mediaAssetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScoreboardObservation> observations(UUID mediaAssetId, UUID analysisId,
                                                    long startTimeMs, long endTimeMs, int limit) {
        return jdbc.query("""
                SELECT observation.* FROM scoreboard_observations observation
                JOIN scoreboard_analyses analysis ON analysis.id = observation.analysis_id
                WHERE analysis.media_asset_id = ? AND analysis.id = ?
                  AND observation.timestamp_ms BETWEEN ? AND ?
                ORDER BY observation.timestamp_ms, observation.kind
                LIMIT ?
                """, (row, index) -> new ScoreboardObservation(
                row.getObject("id", UUID.class), analysisId, row.getLong("timestamp_ms"),
                row.getString("kind"), row.getString("raw_text"),
                (Double) row.getObject("confidence"),
                (Integer) row.getObject("home_score"), (Integer) row.getObject("away_score"),
                (Integer) row.getObject("previous_home_score"), (Integer) row.getObject("previous_away_score"),
                row.getString("details")), mediaAssetId, analysisId, startTimeMs, endTimeMs, limit);
    }

    private static ScoreboardAnalysis mapAnalysis(ResultSet row, int index) throws SQLException {
        Timestamp started = row.getTimestamp("started_at");
        Timestamp completed = row.getTimestamp("completed_at");
        return new ScoreboardAnalysis(row.getObject("id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                ScoreboardAnalysisStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                started == null ? null : started.toInstant(),
                completed == null ? null : completed.toInstant(), row.getString("ocr_model"),
                row.getInt("sampled_frame_count"), row.getInt("ocr_call_count"),
                row.getInt("raw_observation_count"), row.getInt("score_state_count"),
                row.getInt("score_transition_count"), row.getInt("score_reversal_count"),
                row.getInt("processing_error_count"), row.getLong("processing_duration_ms"),
                row.getString("failure_reason"));
    }
}
