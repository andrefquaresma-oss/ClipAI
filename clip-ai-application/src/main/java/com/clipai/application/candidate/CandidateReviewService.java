package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.media.MediaAsset;

import java.time.Clock;
import java.util.UUID;

public final class CandidateReviewService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final CandidateReviewRepository reviews;
    private final Clock clock;
    private final long maximumClipDurationMs;

    public CandidateReviewService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                                  CandidateReviewRepository reviews, Clock clock,
                                  long maximumClipDurationMs) {
        if (maximumClipDurationMs < 1) {
            throw new IllegalArgumentException("maximumClipDurationMs must be positive");
        }
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.reviews = reviews;
        this.clock = clock;
        this.maximumClipDurationMs = maximumClipDurationMs;
    }

    public CandidateReviewResult get(UUID mediaAssetId, UUID candidateId) {
        CandidateEvent candidate = loadCandidate(mediaAssetId, candidateId);
        CandidateReview review = reviews.findByCandidateId(candidateId)
                .orElseGet(() -> new CandidateReview(candidateId, CandidateReviewStatus.UNREVIEWED,
                        null, null, candidate.createdAt()));
        return result(candidate, review);
    }

    public CandidateReviewResult update(UUID mediaAssetId, UUID candidateId, CandidateReviewStatus status,
                                        Long manualStartTimeMs, Long manualEndTimeMs) {
        CandidateReview existing = reviews.findByCandidateId(candidateId)
                .orElse(new CandidateReview(candidateId, CandidateReviewStatus.UNREVIEWED,
                        null, null, null, null, clock.instant()));
        return update(mediaAssetId, candidateId, status, manualStartTimeMs, manualEndTimeMs,
                existing.note(), existing.eventTypeOverride());
    }

    public CandidateReviewResult update(UUID mediaAssetId, UUID candidateId, CandidateReviewStatus status,
                                        Long manualStartTimeMs, Long manualEndTimeMs, String note,
                                        com.clipai.domain.candidate.FootballEventType eventTypeOverride) {
        return update(mediaAssetId, candidateId, status, manualStartTimeMs, manualEndTimeMs,
                note, eventTypeOverride, null);
    }

    public CandidateReviewResult update(UUID mediaAssetId, UUID candidateId, CandidateReviewStatus status,
                                        Long manualStartTimeMs, Long manualEndTimeMs, String note,
                                        com.clipai.domain.candidate.FootballEventType eventTypeOverride,
                                        HumanRejectionReason humanRejectionReason) {
        if (status == null) {
            throw new IllegalArgumentException("review status is required");
        }
        if (status != CandidateReviewStatus.REJECTED) {
            humanRejectionReason = null;
        }
        CandidateEvent candidate = loadCandidate(mediaAssetId, candidateId);
        if (candidate.status() == com.clipai.domain.candidate.CandidateEventStatus.REJECTED
                && status == CandidateReviewStatus.REJECTED && humanRejectionReason == null) {
            throw new IllegalArgumentException("human rejection reason is required for rejected candidates");
        }
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        CandidateReview review = new CandidateReview(candidateId, status,
                manualStartTimeMs, manualEndTimeMs, note, eventTypeOverride,
                humanRejectionReason, clock.instant());
        if (review.hasManualBoundary()) {
            long duration = manualEndTimeMs - manualStartTimeMs;
            if (duration > maximumClipDurationMs) {
                throw new IllegalArgumentException("manual boundary exceeds maximum clip duration");
            }
            if (asset.getDurationMs() == null || manualEndTimeMs > asset.getDurationMs()) {
                throw new IllegalArgumentException("manual boundary must not exceed the known media duration");
            }
        }
        return result(candidate, reviews.save(review));
    }

    private CandidateEvent loadCandidate(UUID mediaAssetId, UUID candidateId) {
        mediaAssets.findById(mediaAssetId).orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        return candidates.findByIdAndMediaAssetId(candidateId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(candidateId, mediaAssetId));
    }

    private CandidateReviewResult result(CandidateEvent candidate, CandidateReview review) {
        long start = review.hasManualBoundary() ? review.manualStartTimeMs() : candidate.startTimeMs();
        long end = review.hasManualBoundary() ? review.manualEndTimeMs() : candidate.endTimeMs();
        return new CandidateReviewResult(candidate, review, start, end, maximumClipDurationMs);
    }
}
