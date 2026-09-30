package com.clipai.infrastructure.persistence.jpa;

import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import com.clipai.domain.transcript.TranscriptStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transcripts")
public class TranscriptJpaEntity {
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false, unique = true)
    private MediaAssetJpaEntity mediaAsset;

    @Column(nullable = false, length = 35)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranscriptStatus status;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "transcript", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startTimeMs ASC, sequence ASC")
    private List<TranscriptSegmentJpaEntity> segments = new ArrayList<>();

    protected TranscriptJpaEntity() {
    }

    static TranscriptJpaEntity fromDomain(Transcript transcript, MediaAssetJpaEntity mediaAsset) {
        TranscriptJpaEntity entity = new TranscriptJpaEntity();
        entity.id = transcript.getId();
        entity.mediaAsset = mediaAsset;
        entity.language = transcript.getLanguage();
        entity.status = transcript.getStatus();
        entity.failureReason = transcript.getFailureReason();
        entity.createdAt = transcript.getCreatedAt();
        entity.updatedAt = transcript.getUpdatedAt();
        entity.segments = new ArrayList<>(transcript.getSegments().stream()
                .map(segment -> TranscriptSegmentJpaEntity.fromDomain(segment, entity)).toList());
        return entity;
    }

    Transcript toDomain() {
        Transcript transcript = Transcript.restore(id, mediaAsset.getId(), language, status,
                failureReason, createdAt, updatedAt);
        segments.forEach(segment -> transcript.addSegment(segment.toDomain(id)));
        return transcript;
    }

    public UUID getId() { return id; }
    public String getLanguage() { return language; }
    public TranscriptStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TranscriptSegmentJpaEntity> getSegments() { return segments; }
}
