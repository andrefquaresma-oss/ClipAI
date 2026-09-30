package com.clipai.domain.transcript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Transcript {
    private final UUID id;
    private final UUID mediaAssetId;
    private String language;
    private final Instant createdAt;
    private final List<TranscriptSegment> segments = new ArrayList<>();
    private TranscriptStatus status;
    private String failureReason;
    private Instant updatedAt;

    private Transcript(UUID id, UUID mediaAssetId, String language, TranscriptStatus status,
                       String failureReason, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "mediaAssetId");
        this.language = normalizeLanguage(language);
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Transcript create(UUID mediaAssetId, String language, Instant now) {
        return new Transcript(UUID.randomUUID(), mediaAssetId, language, TranscriptStatus.PENDING, null, now, now);
    }

    public static Transcript restore(UUID id, UUID mediaAssetId, String language, TranscriptStatus status,
                                     Instant createdAt, Instant updatedAt) {
        return restore(id, mediaAssetId, language, status, null, createdAt, updatedAt);
    }

    public static Transcript restore(UUID id, UUID mediaAssetId, String language, TranscriptStatus status,
                                     String failureReason, Instant createdAt, Instant updatedAt) {
        return new Transcript(id, mediaAssetId, language, status, failureReason, createdAt, updatedAt);
    }

    public void addSegment(TranscriptSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (!id.equals(segment.transcriptId())) {
            throw new IllegalArgumentException("segment belongs to a different transcript");
        }
        if (segments.stream().anyMatch(existing -> existing.sequence() == segment.sequence())) {
            throw new IllegalArgumentException("segment sequence must be unique within a transcript");
        }
        segments.add(segment);
        segments.sort(java.util.Comparator.comparingLong(TranscriptSegment::startTimeMs)
                .thenComparingInt(TranscriptSegment::sequence));
    }

    public void startProcessing(Instant now) {
        requireStatus(TranscriptStatus.PENDING);
        status = TranscriptStatus.PROCESSING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markCompleted(Instant now) {
        markCompleted(language, now);
    }

    public void markCompleted(String detectedLanguage, Instant now) {
        requireStatus(TranscriptStatus.PROCESSING);
        language = normalizeLanguage(detectedLanguage);
        status = TranscriptStatus.COMPLETED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markFailed(Instant now) {
        markFailed("Transcription failed", now);
    }

    public void markFailed(String reason, Instant now) {
        if (status == TranscriptStatus.COMPLETED || status == TranscriptStatus.FAILED) {
            throw new IllegalStateException("Cannot fail a " + status + " transcript");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failureReason must not be blank");
        }
        failureReason = reason.length() > 2000 ? reason.substring(0, 2000) : reason;
        status = TranscriptStatus.FAILED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void resetForRetry(Instant now) {
        if (status != TranscriptStatus.FAILED) {
            throw new IllegalStateException("Only a failed transcript can be retried");
        }
        segments.clear();
        failureReason = null;
        status = TranscriptStatus.PENDING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.trim().length() > 35) {
            throw new IllegalArgumentException("language must contain 1 to 35 characters");
        }
        return language.trim();
    }

    private void requireStatus(TranscriptStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected status " + expected + " but was " + status);
        }
    }

    public UUID getId() { return id; }
    public UUID getMediaAssetId() { return mediaAssetId; }
    public String getLanguage() { return language; }
    public TranscriptStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TranscriptSegment> getSegments() { return List.copyOf(segments); }
}
