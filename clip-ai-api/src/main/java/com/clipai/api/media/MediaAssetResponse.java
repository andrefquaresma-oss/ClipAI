package com.clipai.api.media;

import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.media.MediaAssetPart;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.transcript.Transcript;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MediaAssetResponse(UUID id, String source, String sourceUrl, String externalId,
                                 String title, ContentType contentType, Long durationMs,
                                 String localStoragePath, MediaAssetStatus status,
                                 String failureReason, CandidateDetectionStatus candidateDetectionStatus,
                                 String candidateDetectionFailureReason, Instant createdAt, Instant updatedAt,
                                 TranscriptSummaryResponse transcript, String originalFilename,
                                 String competition, String homeTeam, String awayTeam,
                                 LocalDate matchDate, String language, MediaAssetPart matchPart) {
    public static MediaAssetResponse from(MediaAsset asset) {
        return from(asset, null);
    }

    public static MediaAssetResponse from(MediaAsset asset, Transcript transcript) {
        return new MediaAssetResponse(asset.getId(), asset.getSource(), asset.getSourceUrl(),
                asset.getExternalId(), asset.getTitle(), asset.getContentType(), asset.getDurationMs(),
                asset.getLocalStoragePath(), asset.getStatus(), asset.getFailureReason(),
                asset.getCandidateDetectionStatus(), asset.getCandidateDetectionFailureReason(),
                asset.getCreatedAt(), asset.getUpdatedAt(),
                transcript == null ? null : TranscriptSummaryResponse.from(transcript),
                asset.getOriginalFilename(), asset.getCompetition(), asset.getHomeTeam(),
                asset.getAwayTeam(), asset.getMatchDate(), asset.getLanguage(), asset.getMatchPart());
    }
}
