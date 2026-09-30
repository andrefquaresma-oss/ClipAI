package com.clipai.domain.media;

import com.clipai.domain.candidate.CandidateDetectionStatus;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class MediaAsset {
    private final UUID id;
    private final String source;
    private final String sourceUrl;
    private final String externalId;
    private final String title;
    private final ContentType contentType;
    private final String originalFilename;
    private final String competition;
    private final String homeTeam;
    private final String awayTeam;
    private final LocalDate matchDate;
    private final String language;
    private final MediaAssetPart matchPart;
    private final Instant createdAt;
    private MediaAssetStatus status;
    private Long durationMs;
    private String localStoragePath;
    private String failureReason;
    private CandidateDetectionStatus candidateDetectionStatus;
    private String candidateDetectionFailureReason;
    private Instant updatedAt;

    private MediaAsset(UUID id, String source, String sourceUrl, String externalId, String title,
                       ContentType contentType, Long durationMs, String localStoragePath,
                       String failureReason, MediaAssetStatus status,
                       CandidateDetectionStatus candidateDetectionStatus,
                       String candidateDetectionFailureReason, Instant createdAt, Instant updatedAt,
                       String originalFilename, String competition, String homeTeam,
                       String awayTeam, LocalDate matchDate, String language, MediaAssetPart matchPart) {
        this.id = Objects.requireNonNull(id, "id");
        this.source = requireText(source, "source", 100);
        this.sourceUrl = sourceUrl == null ? null : validateUrl(sourceUrl);
        this.externalId = externalId == null ? null : requireText(externalId, "externalId", 255);
        this.title = requireText(title, "title", 500);
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.originalFilename = optionalText(originalFilename, "originalFilename", 500);
        this.competition = optionalText(competition, "competition", 255);
        this.homeTeam = optionalText(homeTeam, "homeTeam", 255);
        this.awayTeam = optionalText(awayTeam, "awayTeam", 255);
        this.matchDate = matchDate;
        this.language = optionalText(language, "language", 35);
        this.matchPart = matchPart == null ? MediaAssetPart.OTHER : matchPart;
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        this.durationMs = durationMs;
        this.localStoragePath = localStoragePath;
        this.failureReason = failureReason;
        this.status = Objects.requireNonNull(status, "status");
        this.candidateDetectionStatus = Objects.requireNonNull(candidateDetectionStatus,
                "candidateDetectionStatus");
        this.candidateDetectionFailureReason = candidateDetectionFailureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static MediaAsset register(String source, String sourceUrl, String title,
                                      ContentType contentType, Instant now) {
        String validatedUrl = validateUrl(sourceUrl);
        return new MediaAsset(UUID.randomUUID(), source, validatedUrl, null, title, contentType,
                null, null, null, MediaAssetStatus.PENDING, CandidateDetectionStatus.NOT_STARTED,
                null, now, now, null, null, null, null, null, null, MediaAssetPart.OTHER);
    }

    public static MediaAsset registerUpload(String source, String title, ContentType contentType, Instant now) {
        return registerUpload(source, title, contentType, null, null, null, null, null, null,
                MediaAssetPart.OTHER, now);
    }

    public static MediaAsset registerUpload(String source, String title, ContentType contentType,
                                            String originalFilename, String competition, String homeTeam,
                                            String awayTeam, LocalDate matchDate, String language, Instant now) {
        return registerUpload(source, title, contentType, originalFilename, competition, homeTeam,
                awayTeam, matchDate, language, MediaAssetPart.OTHER, now);
    }

    public static MediaAsset registerUpload(String source, String title, ContentType contentType,
                                            String originalFilename, String competition, String homeTeam,
                                            String awayTeam, LocalDate matchDate, String language,
                                            MediaAssetPart matchPart, Instant now) {
        return new MediaAsset(UUID.randomUUID(), source, null, null, title, contentType,
                null, null, null, MediaAssetStatus.PENDING, CandidateDetectionStatus.NOT_STARTED,
                null, now, now, originalFilename, competition, homeTeam, awayTeam, matchDate, language, matchPart);
    }

    public static MediaAsset restore(UUID id, String source, String sourceUrl, String externalId,
                                     String title, ContentType contentType, Long durationMs,
                                     String localStoragePath, MediaAssetStatus status,
                                     Instant createdAt, Instant updatedAt) {
        return restore(id, source, sourceUrl, externalId, title, contentType, durationMs,
                localStoragePath, null, status, createdAt, updatedAt);
    }

    public static MediaAsset restore(UUID id, String source, String sourceUrl, String externalId,
                                     String title, ContentType contentType, Long durationMs,
                                     String localStoragePath, String failureReason, MediaAssetStatus status,
                                     Instant createdAt, Instant updatedAt) {
        return restore(id, source, sourceUrl, externalId, title, contentType, durationMs, localStoragePath,
                failureReason, status, CandidateDetectionStatus.NOT_STARTED, null, createdAt, updatedAt);
    }

    public static MediaAsset restore(UUID id, String source, String sourceUrl, String externalId,
                                     String title, ContentType contentType, Long durationMs,
                                     String localStoragePath, String failureReason, MediaAssetStatus status,
                                     CandidateDetectionStatus candidateDetectionStatus,
                                     String candidateDetectionFailureReason, Instant createdAt, Instant updatedAt) {
        return new MediaAsset(id, source, sourceUrl, externalId, title, contentType, durationMs,
                localStoragePath, failureReason, status, candidateDetectionStatus,
                candidateDetectionFailureReason, createdAt, updatedAt, null, null, null, null, null, null,
                MediaAssetPart.OTHER);
    }

    public static MediaAsset restore(UUID id, String source, String sourceUrl, String externalId,
                                     String title, ContentType contentType, Long durationMs,
                                     String localStoragePath, String failureReason, MediaAssetStatus status,
                                     CandidateDetectionStatus candidateDetectionStatus,
                                     String candidateDetectionFailureReason, Instant createdAt, Instant updatedAt,
                                     String originalFilename, String competition, String homeTeam,
                                     String awayTeam, LocalDate matchDate, String language) {
        return restore(id, source, sourceUrl, externalId, title, contentType, durationMs, localStoragePath,
                failureReason, status, candidateDetectionStatus, candidateDetectionFailureReason,
                createdAt, updatedAt, originalFilename, competition, homeTeam, awayTeam, matchDate,
                language, MediaAssetPart.OTHER);
    }

    public static MediaAsset restore(UUID id, String source, String sourceUrl, String externalId,
                                     String title, ContentType contentType, Long durationMs,
                                     String localStoragePath, String failureReason, MediaAssetStatus status,
                                     CandidateDetectionStatus candidateDetectionStatus,
                                     String candidateDetectionFailureReason, Instant createdAt, Instant updatedAt,
                                     String originalFilename, String competition, String homeTeam,
                                     String awayTeam, LocalDate matchDate, String language,
                                     MediaAssetPart matchPart) {
        return new MediaAsset(id, source, sourceUrl, externalId, title, contentType, durationMs,
                localStoragePath, failureReason, status, candidateDetectionStatus,
                candidateDetectionFailureReason, createdAt, updatedAt, originalFilename,
                competition, homeTeam, awayTeam, matchDate, language, matchPart);
    }

    public void startDownloading(Instant now) {
        requireStatus(MediaAssetStatus.PENDING);
        status = MediaAssetStatus.DOWNLOADING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markReady(String storagePath, Long durationMs, Instant now) {
        requireStatus(MediaAssetStatus.DOWNLOADING);
        localStoragePath = requireText(storagePath, "storagePath", 2048);
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        this.durationMs = durationMs;
        status = MediaAssetStatus.READY;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markStored(String storageKey, Instant now) {
        requireStatus(MediaAssetStatus.PENDING);
        localStoragePath = requireText(storageKey, "storageKey", 2048);
        status = MediaAssetStatus.STORED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void startProcessing(Instant now) {
        if (status != MediaAssetStatus.STORED && status != MediaAssetStatus.READY) {
            throw new IllegalStateException("Expected status STORED or READY but was " + status);
        }
        status = MediaAssetStatus.PROCESSING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void startTranscribing(Instant now) {
        if (status != MediaAssetStatus.PROCESSING && status != MediaAssetStatus.AUDIO_EXTRACTED) {
            throw new IllegalStateException("Expected status PROCESSING or AUDIO_EXTRACTED but was " + status);
        }
        status = MediaAssetStatus.TRANSCRIBING;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markAudioExtracted(Instant now) {
        requireStatus(MediaAssetStatus.PROCESSING);
        status = MediaAssetStatus.AUDIO_EXTRACTED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markCompleted(Instant now) {
        if (status != MediaAssetStatus.PROCESSING && status != MediaAssetStatus.AUDIO_EXTRACTED
                && status != MediaAssetStatus.TRANSCRIBING) {
            throw new IllegalStateException("Expected status PROCESSING, AUDIO_EXTRACTED or TRANSCRIBING but was "
                    + status);
        }
        status = MediaAssetStatus.COMPLETED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markFailed(Instant now) {
        markFailed("Media processing failed", now);
    }

    public void markFailed(String reason, Instant now) {
        if (status == MediaAssetStatus.COMPLETED || status == MediaAssetStatus.FAILED) {
            throw new IllegalStateException("Cannot fail a " + status + " media asset");
        }
        failureReason = requireText(reason, "failureReason", 2000);
        status = MediaAssetStatus.FAILED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void retryProcessing(Instant now) {
        requireStatus(MediaAssetStatus.FAILED);
        if (localStoragePath == null || localStoragePath.isBlank()) {
            throw new IllegalStateException("A stored media file is required to retry processing");
        }
        status = MediaAssetStatus.STORED;
        failureReason = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void startCandidateDetection(Instant now) {
        if (status != MediaAssetStatus.COMPLETED) {
            throw new IllegalStateException("Candidate detection requires a completed media asset");
        }
        if (candidateDetectionStatus == CandidateDetectionStatus.PROCESSING) {
            throw new IllegalStateException("Candidate detection is already processing");
        }
        candidateDetectionStatus = CandidateDetectionStatus.PROCESSING;
        candidateDetectionFailureReason = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void completeCandidateDetection(Instant now) {
        requireCandidateDetectionStatus(CandidateDetectionStatus.PROCESSING);
        candidateDetectionStatus = CandidateDetectionStatus.COMPLETED;
        candidateDetectionFailureReason = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void failCandidateDetection(String reason, Instant now) {
        requireCandidateDetectionStatus(CandidateDetectionStatus.PROCESSING);
        candidateDetectionFailureReason = requireText(reason, "candidateDetectionFailureReason", 2000);
        candidateDetectionStatus = CandidateDetectionStatus.FAILED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private void requireCandidateDetectionStatus(CandidateDetectionStatus expected) {
        if (candidateDetectionStatus != expected) {
            throw new IllegalStateException("Expected candidate detection status " + expected
                    + " but was " + candidateDetectionStatus);
        }
    }

    private void requireStatus(MediaAssetStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected status " + expected + " but was " + status);
        }
    }

    private static String validateUrl(String value) {
        String url = requireText(value, "sourceUrl", 2048);
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("sourceUrl must be an absolute HTTP or HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("sourceUrl must be")) {
                throw exception;
            }
            throw new IllegalArgumentException("sourceUrl must be a valid absolute HTTP or HTTPS URL", exception);
        }
        return url;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        return value == null || value.isBlank() ? null : requireText(value, field, maxLength);
    }

    public UUID getId() { return id; }
    public String getSource() { return source; }
    public String getSourceUrl() { return sourceUrl; }
    public String getExternalId() { return externalId; }
    public String getTitle() { return title; }
    public ContentType getContentType() { return contentType; }
    public String getOriginalFilename() { return originalFilename; }
    public String getCompetition() { return competition; }
    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
    public LocalDate getMatchDate() { return matchDate; }
    public String getLanguage() { return language; }
    public MediaAssetPart getMatchPart() { return matchPart; }
    public Long getDurationMs() { return durationMs; }
    public String getLocalStoragePath() { return localStoragePath; }
    public String getFailureReason() { return failureReason; }
    public MediaAssetStatus getStatus() { return status; }
    public CandidateDetectionStatus getCandidateDetectionStatus() { return candidateDetectionStatus; }
    public String getCandidateDetectionFailureReason() { return candidateDetectionFailureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
