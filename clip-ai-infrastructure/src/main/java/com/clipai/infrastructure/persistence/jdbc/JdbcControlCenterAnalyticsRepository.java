package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.analytics.ControlCenterAnalytics;
import com.clipai.application.analytics.ControlCenterAnalyticsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class JdbcControlCenterAnalyticsRepository implements ControlCenterAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public JdbcControlCenterAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ControlCenterAnalytics load(long generatedClips) {
        Map<String, Long> media = counts("SELECT status, count(*) FROM media_assets GROUP BY status");
        Map<String, Long> eventTypes = counts("SELECT event_type, count(*) FROM candidate_events GROUP BY event_type");
        Map<String, Long> detection = counts("SELECT detection_status, count(*) FROM candidate_events GROUP BY detection_status");
        Map<String, Long> runs = counts("SELECT status, count(*) FROM detection_runs GROUP BY status");
        Map<String, Long> stages = counts("SELECT status, count(*) FROM media_processing_stage_runs GROUP BY status");
        long assetCount = total("SELECT count(*) FROM media_assets");
        long eventCount = total("SELECT count(*) FROM candidate_events");
        long reviewed = total("SELECT count(*) FROM ground_truth_match_reviews");
        long completed = total("""
                SELECT count(*) FROM ground_truth_match_reviews WHERE review_status = 'COMPLETED'
                """);
        long groundTruthEvents = total("SELECT count(*) FROM ground_truth_events");
        long awaitingReview = total("""
                SELECT count(*) FROM candidate_events event
                LEFT JOIN candidate_reviews review ON review.candidate_event_id = event.id
                WHERE event.detection_status <> 'REJECTED'
                  AND COALESCE(review.review_status, 'UNREVIEWED') = 'UNREVIEWED'
                """);
        long rejectedAwaitingReview = total("""
                SELECT count(*) FROM candidate_events event
                LEFT JOIN candidate_reviews review ON review.candidate_event_id = event.id
                WHERE event.detection_status = 'REJECTED'
                  AND COALESCE(review.review_status, 'UNREVIEWED') <> 'REJECTED'
                """);
        long evaluationReady = total("""
                SELECT count(*) FROM ground_truth_match_reviews review
                WHERE review.review_status = 'COMPLETED'
                  AND (SELECT count(*) FROM ground_truth_events event
                       WHERE event.media_asset_id = review.media_asset_id) >= 5
                """);
        return new ControlCenterAnalytics(assetCount, media, eventCount, eventTypes, detection,
                generatedClips, reviewed, completed, groundTruthEvents, evaluationReady,
                awaitingReview, rejectedAwaitingReview, runs, stages,
                "Match review must be complete and include at least 5 ground-truth events.");
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler)
                row -> result.put(row.getString(1), row.getLong(2)));
        return result;
    }

    private long total(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }
}
