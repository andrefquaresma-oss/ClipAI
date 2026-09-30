package com.clipai.infrastructure.persistence.jpa;

import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetPart;
import com.clipai.domain.media.MediaAssetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAssetJpaEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(length = 255)
    private String competition;

    @Column(name = "home_team", length = 255)
    private String homeTeam;

    @Column(name = "away_team", length = 255)
    private String awayTeam;

    @Column(name = "match_date")
    private LocalDate matchDate;

    @Column(length = 35)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_part", nullable = false, length = 20)
    private MediaAssetPart matchPart;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 40)
    private ContentType contentType;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "local_storage_path", length = 2048)
    private String localStoragePath;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaAssetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_detection_status", nullable = false, length = 20)
    private CandidateDetectionStatus candidateDetectionStatus;

    @Column(name = "candidate_detection_failure_reason", columnDefinition = "text")
    private String candidateDetectionFailureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaAssetJpaEntity() {
    }

    static MediaAssetJpaEntity fromDomain(MediaAsset asset) {
        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.id = asset.getId();
        entity.source = asset.getSource();
        entity.sourceUrl = asset.getSourceUrl();
        entity.externalId = asset.getExternalId();
        entity.title = asset.getTitle();
        entity.originalFilename = asset.getOriginalFilename();
        entity.competition = asset.getCompetition();
        entity.homeTeam = asset.getHomeTeam();
        entity.awayTeam = asset.getAwayTeam();
        entity.matchDate = asset.getMatchDate();
        entity.language = asset.getLanguage();
        entity.matchPart = asset.getMatchPart();
        entity.contentType = asset.getContentType();
        entity.durationMs = asset.getDurationMs();
        entity.localStoragePath = asset.getLocalStoragePath();
        entity.failureReason = asset.getFailureReason();
        entity.status = asset.getStatus();
        entity.candidateDetectionStatus = asset.getCandidateDetectionStatus();
        entity.candidateDetectionFailureReason = asset.getCandidateDetectionFailureReason();
        entity.createdAt = asset.getCreatedAt();
        entity.updatedAt = asset.getUpdatedAt();
        return entity;
    }

    MediaAsset toDomain() {
        return MediaAsset.restore(id, source, sourceUrl, externalId, title, contentType, durationMs,
                localStoragePath, failureReason, status, candidateDetectionStatus,
                candidateDetectionFailureReason, createdAt, updatedAt, originalFilename,
                competition, homeTeam, awayTeam, matchDate, language, matchPart);
    }

    public UUID getId() {
        return id;
    }
}
