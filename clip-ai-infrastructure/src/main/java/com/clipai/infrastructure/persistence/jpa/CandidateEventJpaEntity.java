package com.clipai.infrastructure.persistence.jpa;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateScoreComponent;
import com.clipai.domain.candidate.FootballEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "candidate_events")
public class CandidateEventJpaEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAssetJpaEntity mediaAsset;

    @Column(name = "detection_run_id")
    private UUID detectionRunId;

    @Column(name = "start_time_ms", nullable = false)
    private long startTimeMs;

    @Column(name = "end_time_ms", nullable = false)
    private long endTimeMs;

    @Column(name = "trigger_timestamp_ms", nullable = false)
    private long triggerTimestampMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private FootballEventType eventType;

    @Column(name = "candidate_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals", nullable = false, columnDefinition = "jsonb")
    private List<CandidateSignal> signals;

    @Column(name = "transcript_context", columnDefinition = "text")
    private String transcriptContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "detection_status", nullable = false, length = 24)
    private CandidateEventStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_candidate_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> sourceCandidateIds;

    @Column(name = "merge_reason", length = 500)
    private String mergeReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_contributions", nullable = false, columnDefinition = "jsonb")
    private List<CandidateScoreComponent> scoreContributions;

    protected CandidateEventJpaEntity() {
    }

    static CandidateEventJpaEntity fromDomain(CandidateEvent event, MediaAssetJpaEntity mediaAsset) {
        return fromDomain(event, mediaAsset, event.detectionRunId());
    }

    static CandidateEventJpaEntity fromDomain(CandidateEvent event, MediaAssetJpaEntity mediaAsset,
                                              UUID detectionRunId) {
        CandidateEventJpaEntity entity = new CandidateEventJpaEntity();
        entity.id = event.id();
        entity.mediaAsset = mediaAsset;
        entity.detectionRunId = detectionRunId;
        entity.startTimeMs = event.startTimeMs();
        entity.endTimeMs = event.endTimeMs();
        entity.triggerTimestampMs = event.triggerTimestampMs();
        entity.eventType = event.eventType();
        entity.score = BigDecimal.valueOf(event.score());
        entity.signals = event.signals();
        entity.transcriptContext = event.transcriptContext();
        entity.status = event.status();
        entity.createdAt = event.createdAt();
        entity.sourceCandidateIds = event.sourceCandidateIds();
        entity.mergeReason = event.mergeReason();
        entity.scoreContributions = event.scoreContributions();
        return entity;
    }

    CandidateEvent toDomain() {
        return new CandidateEvent(id, mediaAsset.getId(), startTimeMs, endTimeMs, triggerTimestampMs,
                eventType, score.doubleValue(), signals, transcriptContext, status, createdAt,
                sourceCandidateIds, mergeReason, scoreContributions, detectionRunId);
    }
}
