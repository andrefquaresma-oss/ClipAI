package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.candidate.CandidateReview;
import com.clipai.application.candidate.CandidateReviewRepository;
import com.clipai.application.candidate.CandidateReviewStatus;
import com.clipai.application.candidate.HumanRejectionReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CandidateReviewRepositoryAdapter implements CandidateReviewRepository {
    private final JdbcTemplate jdbc;

    public CandidateReviewRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CandidateReview> findByCandidateId(UUID candidateId) {
        return jdbc.query("""
                SELECT candidate_event_id, review_status, manual_start_time_ms, manual_end_time_ms,
                       review_note, event_type_override, human_rejection_reason, updated_at
                FROM candidate_reviews
                WHERE candidate_event_id = ?
                """, (row, index) -> new CandidateReview(
                row.getObject("candidate_event_id", UUID.class),
                CandidateReviewStatus.valueOf(row.getString("review_status")),
                (Long) row.getObject("manual_start_time_ms"),
                (Long) row.getObject("manual_end_time_ms"),
                row.getString("review_note"),
                row.getString("event_type_override") == null ? null
                        : com.clipai.domain.candidate.FootballEventType.valueOf(
                                row.getString("event_type_override")),
                row.getString("human_rejection_reason") == null ? null
                        : HumanRejectionReason.valueOf(row.getString("human_rejection_reason")),
                row.getTimestamp("updated_at").toInstant()), candidateId).stream().findFirst();
    }

    @Override
    @Transactional
    public CandidateReview save(CandidateReview review) {
        jdbc.update("""
                INSERT INTO candidate_reviews
                    (candidate_event_id, review_status, manual_start_time_ms, manual_end_time_ms,
                     review_note, event_type_override, human_rejection_reason, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_event_id) DO UPDATE SET
                    review_status = EXCLUDED.review_status,
                    manual_start_time_ms = EXCLUDED.manual_start_time_ms,
                    manual_end_time_ms = EXCLUDED.manual_end_time_ms,
                    review_note = EXCLUDED.review_note,
                    event_type_override = EXCLUDED.event_type_override,
                    human_rejection_reason = EXCLUDED.human_rejection_reason,
                    updated_at = EXCLUDED.updated_at
                """, review.candidateId(), review.status().name(), review.manualStartTimeMs(),
                review.manualEndTimeMs(), review.note(),
                review.eventTypeOverride() == null ? null : review.eventTypeOverride().name(),
                review.humanRejectionReason() == null ? null : review.humanRejectionReason().name(),
                Timestamp.from(review.updatedAt()));
        return review;
    }
}
