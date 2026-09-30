package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.candidate.CandidateObservationRepository;
import com.clipai.domain.candidate.CandidateObservation;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Repository
public class CandidateObservationRepositoryAdapter implements CandidateObservationRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CandidateObservationRepositoryAdapter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void appendForRun(UUID detectionRunId, UUID mediaAssetId, List<CandidateSignal> signals) {
        append(detectionRunId, mediaAssetId, signals);
    }

    @Override
    @Transactional
    public void appendLegacyForMediaAsset(UUID mediaAssetId, List<CandidateSignal> signals) {
        append(null, mediaAssetId, signals);
    }

    private void append(UUID detectionRunId, UUID mediaAssetId, List<CandidateSignal> signals) {
        Timestamp observedAt = Timestamp.from(clock.instant());
        List<Object[]> rows = signals.stream().distinct().map(signal -> new Object[] {
                UUID.randomUUID(), mediaAssetId, detectionRunId, signal.timestampMs(), signal.type().name(),
                signal.eventType() == null ? null : signal.eventType().name(),
                signal.confidence(), signal.evidence(), observedAt
        }).toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO candidate_observations
                        (id, media_asset_id, detection_run_id, timestamp_ms, signal_type,
                         event_type, confidence, evidence, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long countByMediaAssetIdAndRunId(UUID mediaAssetId, UUID detectionRunId) {
        String sql = detectionRunId == null
                ? "SELECT count(*) FROM candidate_observations WHERE media_asset_id = ? AND detection_run_id IS NULL"
                : "SELECT count(*) FROM candidate_observations WHERE media_asset_id = ? AND detection_run_id = ?";
        return detectionRunId == null
                ? jdbc.queryForObject(sql, Long.class, mediaAssetId)
                : jdbc.queryForObject(sql, Long.class, mediaAssetId, detectionRunId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateObservation> findByMediaAssetIdAndTimestampRange(
            UUID mediaAssetId, long startTimeMs, long endTimeMs, int limit) {
        return findByMediaAssetIdAndRunIdAndTimestampRange(
                mediaAssetId, null, startTimeMs, endTimeMs, limit, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateObservation> findByMediaAssetIdAndRunIdAndTimestampRange(
            UUID mediaAssetId, UUID detectionRunId, long startTimeMs, long endTimeMs, int limit) {
        return findByMediaAssetIdAndRunIdAndTimestampRange(
                mediaAssetId, detectionRunId, startTimeMs, endTimeMs, limit, false);
    }

    private List<CandidateObservation> findByMediaAssetIdAndRunIdAndTimestampRange(
            UUID mediaAssetId, UUID detectionRunId, long startTimeMs, long endTimeMs,
            int limit, boolean allRuns) {
        String runFilter = allRuns ? "" : detectionRunId == null
                ? "AND detection_run_id IS NULL" : "AND detection_run_id = ?";
        Object[] parameters = allRuns
                ? new Object[] {mediaAssetId, startTimeMs, endTimeMs, limit, limit}
                : detectionRunId == null
                    ? new Object[] {mediaAssetId, startTimeMs, endTimeMs, limit, limit}
                    : new Object[] {mediaAssetId, detectionRunId, startTimeMs, endTimeMs, limit, limit};
        String sql = """
                WITH ranked AS (
                    SELECT id, media_asset_id, detection_run_id, timestamp_ms, signal_type, event_type,
                           confidence, evidence, observed_at,
                           row_number() OVER (ORDER BY timestamp_ms, id) AS observation_number,
                           count(*) OVER () AS observation_count
                    FROM candidate_observations
                    WHERE media_asset_id = ? %s AND timestamp_ms BETWEEN ? AND ?
                ),
                sampled AS (
                    SELECT ranked.*,
                           row_number() OVER (
                               PARTITION BY floor((observation_number - 1)
                                   * CAST(? AS numeric) / observation_count)
                               ORDER BY observation_number
                           ) AS sample_rank
                    FROM ranked
                )
                SELECT id, media_asset_id, detection_run_id, timestamp_ms, signal_type,
                       event_type, confidence, evidence, observed_at
                FROM sampled
                WHERE sample_rank = 1
                ORDER BY timestamp_ms, id
                LIMIT ?
                """.formatted(runFilter);
        return jdbc.query(sql, (row, index) -> new CandidateObservation(
                row.getObject("id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                row.getObject("detection_run_id", UUID.class),
                new CandidateSignal(
                        CandidateSignalType.valueOf(row.getString("signal_type")),
                        row.getString("event_type") == null ? null
                                : FootballEventType.valueOf(row.getString("event_type")),
                        row.getDouble("confidence"), row.getLong("timestamp_ms"),
                        row.getString("evidence")),
                row.getTimestamp("observed_at").toInstant()),
                parameters);
    }
}
