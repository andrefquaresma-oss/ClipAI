package com.clipai.infrastructure.persistence.jpa;

import com.clipai.domain.transcript.TranscriptSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "transcript_segments",
        uniqueConstraints = @UniqueConstraint(name = "uq_transcript_segments_sequence",
                columnNames = {"transcript_id", "sequence"}))
public class TranscriptSegmentJpaEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transcript_id", nullable = false)
    private TranscriptJpaEntity transcript;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "start_time_ms", nullable = false)
    private long startTimeMs;

    @Column(name = "end_time_ms", nullable = false)
    private long endTimeMs;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    protected TranscriptSegmentJpaEntity() {
    }

    static TranscriptSegmentJpaEntity fromDomain(TranscriptSegment segment, TranscriptJpaEntity transcript) {
        TranscriptSegmentJpaEntity entity = new TranscriptSegmentJpaEntity();
        entity.id = segment.id();
        entity.transcript = transcript;
        entity.sequence = segment.sequence();
        entity.startTimeMs = segment.startTimeMs();
        entity.endTimeMs = segment.endTimeMs();
        entity.text = segment.text();
        return entity;
    }

    TranscriptSegment toDomain(UUID transcriptId) {
        return new TranscriptSegment(id, transcriptId, sequence, startTimeMs, endTimeMs, text);
    }

    public UUID getId() { return id; }
    public int getSequence() { return sequence; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public String getText() { return text; }
}
