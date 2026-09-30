package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.groundtruth.GroundTruthEvent;
import com.clipai.application.groundtruth.GroundTruthMatchReview;
import com.clipai.application.groundtruth.GroundTruthRepository;
import com.clipai.application.groundtruth.GroundTruthReviewStatus;
import com.clipai.domain.candidate.FootballEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGroundTruthRepository implements GroundTruthRepository {
    private final JdbcTemplate jdbc;

    public JdbcGroundTruthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroundTruthMatchReview> findReview(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT media_asset_id, review_status, updated_at
                FROM ground_truth_match_reviews WHERE media_asset_id = ?
                """, (row, index) -> new GroundTruthMatchReview(
                row.getObject("media_asset_id", UUID.class),
                GroundTruthReviewStatus.valueOf(row.getString("review_status")),
                row.getTimestamp("updated_at").toInstant()), mediaAssetId).stream().findFirst();
    }

    @Override
    @Transactional
    public GroundTruthMatchReview saveReview(GroundTruthMatchReview review) {
        jdbc.update("""
                INSERT INTO ground_truth_match_reviews (media_asset_id, review_status, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (media_asset_id) DO UPDATE SET
                    review_status = EXCLUDED.review_status, updated_at = EXCLUDED.updated_at
                """, review.mediaAssetId(), review.status().name(), Timestamp.from(review.updatedAt()));
        return review;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroundTruthEvent> findEvents(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT id, media_asset_id, source_candidate_id, event_type, timestamp_ms,
                       start_time_ms, end_time_ms, note, created_at, updated_at
                FROM ground_truth_events
                WHERE media_asset_id = ?
                ORDER BY timestamp_ms, id
                """, (row, index) -> new GroundTruthEvent(
                row.getObject("id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                row.getObject("source_candidate_id", UUID.class),
                FootballEventType.valueOf(row.getString("event_type")),
                row.getLong("timestamp_ms"), row.getLong("start_time_ms"), row.getLong("end_time_ms"),
                row.getString("note"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), mediaAssetId);
    }

    @Override
    @Transactional
    public GroundTruthEvent saveEvent(GroundTruthEvent event) {
        jdbc.update("""
                INSERT INTO ground_truth_events
                    (id, media_asset_id, source_candidate_id, event_type, timestamp_ms,
                     start_time_ms, end_time_ms, note, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET event_type = EXCLUDED.event_type,
                    timestamp_ms = EXCLUDED.timestamp_ms, start_time_ms = EXCLUDED.start_time_ms,
                    end_time_ms = EXCLUDED.end_time_ms, note = EXCLUDED.note,
                    updated_at = EXCLUDED.updated_at
                """, event.id(), event.mediaAssetId(), event.sourceCandidateId(), event.eventType().name(),
                event.timestampMs(), event.startTimeMs(), event.endTimeMs(), event.note(),
                Timestamp.from(event.createdAt()), Timestamp.from(event.updatedAt()));
        return event;
    }

    @Override
    @Transactional
    public void deleteEvent(UUID eventId, UUID mediaAssetId) {
        jdbc.update("DELETE FROM ground_truth_events WHERE id = ? AND media_asset_id = ?",
                eventId, mediaAssetId);
    }
}
