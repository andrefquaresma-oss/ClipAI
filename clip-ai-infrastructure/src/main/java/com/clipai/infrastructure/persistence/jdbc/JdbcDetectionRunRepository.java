package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.candidate.DetectionRunRepository;
import com.clipai.domain.candidate.DetectionRun;
import com.clipai.domain.candidate.DetectionRunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDetectionRunRepository implements DetectionRunRepository {
    private final JdbcTemplate jdbc;

    public JdbcDetectionRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean createPending(DetectionRun run) {
        return jdbc.update("""
                INSERT INTO detection_runs
                    (id, media_asset_id, created_at, status, detector_version, configuration_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, run.id(), run.mediaAssetId(), Timestamp.from(run.createdAt()), run.status().name(),
                run.detectorVersion(), run.configurationHash()) == 1;
    }

    @Override
    @Transactional
    public boolean markRunning(UUID runId, Instant startedAt) {
        return jdbc.update("""
                UPDATE detection_runs
                   SET status = ?, started_at = ?
                 WHERE id = ? AND status = ?
                """, DetectionRunStatus.RUNNING.name(), Timestamp.from(startedAt),
                runId, DetectionRunStatus.PENDING.name()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DetectionRun> findById(UUID runId) {
        return jdbc.query("""
                SELECT id, media_asset_id, created_at, started_at, completed_at, status,
                       detector_version, configuration_hash, candidate_count, observation_count,
                       detected_count, rejected_count, failure_reason
                FROM detection_runs WHERE id = ?
                """, JdbcDetectionRunRepository::mapRun, runId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DetectionRun> findByMediaAssetId(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT id, media_asset_id, created_at, started_at, completed_at, status,
                       detector_version, configuration_hash, candidate_count, observation_count,
                       detected_count, rejected_count, failure_reason
                FROM detection_runs
                WHERE media_asset_id = ?
                ORDER BY created_at DESC, id DESC
                """, JdbcDetectionRunRepository::mapRun, mediaAssetId);
    }

    @Override
    @Transactional
    public void markFailed(UUID runId, Instant completedAt, String failureReason) {
        String reason = failureReason == null || failureReason.isBlank()
                ? "Candidate detection failed" : failureReason;
        if (reason.length() > 1000) {
            reason = reason.substring(0, 1000);
        }
        jdbc.update("""
                UPDATE detection_runs
                   SET status = ?, completed_at = ?, failure_reason = ?
                 WHERE id = ? AND status IN (?, ?)
                """, DetectionRunStatus.FAILED.name(), Timestamp.from(completedAt), reason, runId,
                DetectionRunStatus.PENDING.name(), DetectionRunStatus.RUNNING.name());
        jdbc.update("""
                UPDATE media_assets
                   SET candidate_detection_status = 'FAILED',
                       candidate_detection_failure_reason = 'Candidate detection failed',
                       updated_at = ?
                 WHERE id = (SELECT media_asset_id FROM detection_runs WHERE id = ?)
                   AND candidate_detection_status = 'PROCESSING'
                """, Timestamp.from(completedAt), runId);
    }

    private static DetectionRun mapRun(ResultSet row, int index) throws SQLException {
        return new DetectionRun(row.getObject("id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                instant(row, "started_at"), instant(row, "completed_at"),
                DetectionRunStatus.valueOf(row.getString("status")),
                row.getString("detector_version"), row.getString("configuration_hash"),
                row.getInt("candidate_count"), row.getInt("observation_count"),
                row.getInt("detected_count"), row.getInt("rejected_count"),
                row.getString("failure_reason"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
