package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.CandidateObservationRepository;
import com.clipai.application.candidate.DetectionRunResultWriter;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDetectionRunResultWriter implements DetectionRunResultWriter {
    private final CandidateEventRepository candidates;
    private final CandidateObservationRepository observations;
    private final JdbcTemplate jdbc;

    public JdbcDetectionRunResultWriter(CandidateEventRepository candidates,
                                        CandidateObservationRepository observations,
                                        JdbcTemplate jdbc) {
        this.candidates = candidates;
        this.observations = observations;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void persistCompleted(UUID runId, UUID mediaAssetId, List<CandidateSignal> signals,
                                 List<CandidateEvent> events, Instant completedAt) {
        if (events.stream().anyMatch(event -> !mediaAssetId.equals(event.mediaAssetId())
                || !runId.equals(event.detectionRunId()))) {
            throw new IllegalArgumentException("all candidates must belong to the completed run and media asset");
        }
        List<CandidateSignal> distinctSignals = signals.stream().distinct().toList();
        candidates.appendForRun(runId, mediaAssetId, events);
        observations.appendForRun(runId, mediaAssetId, distinctSignals);
        int detected = (int) events.stream()
                .filter(event -> event.status() == CandidateEventStatus.DETECTED).count();
        int rejected = (int) events.stream()
                .filter(event -> event.status() == CandidateEventStatus.REJECTED).count();
        int updated = jdbc.update("""
                UPDATE detection_runs
                   SET status = 'COMPLETED', completed_at = ?, candidate_count = ?,
                       observation_count = ?, detected_count = ?, rejected_count = ?, failure_reason = NULL
                 WHERE id = ? AND media_asset_id = ? AND status = 'RUNNING'
                """, Timestamp.from(completedAt), events.size(), distinctSignals.size(),
                detected, rejected, runId, mediaAssetId);
        if (updated != 1) {
            throw new IllegalStateException("Detection run is not active or does not belong to the media asset");
        }
        int assetUpdated = jdbc.update("""
                UPDATE media_assets
                   SET candidate_detection_status = 'COMPLETED',
                       candidate_detection_failure_reason = NULL,
                       updated_at = ?
                 WHERE id = ? AND candidate_detection_status = 'PROCESSING'
                """, Timestamp.from(completedAt), mediaAssetId);
        if (assetUpdated != 1) {
            throw new IllegalStateException("Media asset detection state changed before run completion");
        }
    }
}
