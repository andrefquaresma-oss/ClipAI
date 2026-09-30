package com.clipai.application.groundtruth;

import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.CandidateReviewRepository;
import com.clipai.application.candidate.CandidateReviewStatus;
import com.clipai.application.candidate.CandidateEventNotFoundException;
import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.MediaAsset;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class GroundTruthService {
    public static final int MINIMUM_EVENTS_FOR_EVALUATION = 5;
    private static final long EVENT_MATCH_TOLERANCE_MS = 15_000;
    private final MediaAssetRepository assets;
    private final CandidateEventRepository candidates;
    private final CandidateReviewRepository reviews;
    private final GroundTruthRepository groundTruth;
    private final Clock clock;

    public GroundTruthService(MediaAssetRepository assets, CandidateEventRepository candidates,
                              CandidateReviewRepository reviews, GroundTruthRepository groundTruth,
                              Clock clock) {
        this.assets = assets;
        this.candidates = candidates;
        this.reviews = reviews;
        this.groundTruth = groundTruth;
        this.clock = clock;
    }

    public GroundTruthMatch get(UUID mediaAssetId) {
        MediaAsset asset = requireAsset(mediaAssetId);
        List<GroundTruthEvent> expected = groundTruth.findEvents(mediaAssetId);
        List<CandidateEvent> detected = candidates.findByMediaAssetId(mediaAssetId).stream()
                .filter(event -> event.status() != com.clipai.domain.candidate.CandidateEventStatus.REJECTED)
                .toList();
        GroundTruthReviewStatus status = groundTruth.findReview(mediaAssetId)
                .map(GroundTruthMatchReview::status).orElse(GroundTruthReviewStatus.IN_PROGRESS);
        return new GroundTruthMatch(asset, status, expected, calculate(status, expected, detected));
    }

    public GroundTruthMatch setStatus(UUID mediaAssetId, GroundTruthReviewStatus status) {
        requireAsset(mediaAssetId);
        if (status == null) {
            throw new IllegalArgumentException("review status is required");
        }
        groundTruth.saveReview(new GroundTruthMatchReview(mediaAssetId, status, clock.instant()));
        return get(mediaAssetId);
    }

    public GroundTruthEvent create(UUID mediaAssetId, FootballEventType eventType, long timestampMs,
                                   long startTimeMs, long endTimeMs, String note) {
        MediaAsset asset = requireAsset(mediaAssetId);
        validateWindow(asset, timestampMs, startTimeMs, endTimeMs, note);
        GroundTruthEvent event = new GroundTruthEvent(UUID.randomUUID(), mediaAssetId, null, eventType,
                timestampMs, startTimeMs, endTimeMs, note, clock.instant(), clock.instant());
        groundTruth.saveEvent(event);
        ensureInProgress(mediaAssetId);
        return event;
    }

    public GroundTruthEvent confirmCandidate(UUID mediaAssetId, UUID candidateId, String note) {
        requireAsset(mediaAssetId);
        GroundTruthEvent alreadyConfirmed = groundTruth.findEvents(mediaAssetId).stream()
                .filter(event -> candidateId.equals(event.sourceCandidateId()))
                .findFirst().orElse(null);
        if (alreadyConfirmed != null) {
            return alreadyConfirmed;
        }
        CandidateEvent candidate = candidates.findByIdAndMediaAssetId(candidateId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(candidateId, mediaAssetId));
        if (candidate.status() == com.clipai.domain.candidate.CandidateEventStatus.REJECTED) {
            throw new IllegalArgumentException("Rejected candidates cannot be confirmed as ground truth");
        }
        var review = reviews.findByCandidateId(candidateId).orElse(null);
        FootballEventType type = review != null && review.eventTypeOverride() != null
                ? review.eventTypeOverride() : candidate.eventType();
        long start = review != null && review.hasManualBoundary()
                ? review.manualStartTimeMs() : candidate.startTimeMs();
        long end = review != null && review.hasManualBoundary()
                ? review.manualEndTimeMs() : candidate.endTimeMs();
        long timestamp = Math.max(start, Math.min(candidate.triggerTimestampMs(), end));
        GroundTruthEvent event = new GroundTruthEvent(UUID.randomUUID(), mediaAssetId, candidateId,
                type, timestamp, start, end, note, clock.instant(), clock.instant());
        groundTruth.saveEvent(event);
        ensureInProgress(mediaAssetId);
        if (review == null || review.status() != CandidateReviewStatus.CONFIRMED) {
            reviews.save(new com.clipai.application.candidate.CandidateReview(candidateId,
                        CandidateReviewStatus.CONFIRMED,
                        review == null ? null : review.manualStartTimeMs(),
                        review == null ? null : review.manualEndTimeMs(),
                        note, type, clock.instant()));
        }
        return event;
    }

    public void delete(UUID mediaAssetId, UUID eventId) {
        requireAsset(mediaAssetId);
        groundTruth.deleteEvent(eventId, mediaAssetId);
    }

    private void ensureInProgress(UUID mediaAssetId) {
        GroundTruthMatchReview current = groundTruth.findReview(mediaAssetId).orElse(null);
        if (current == null || current.status() != GroundTruthReviewStatus.IN_PROGRESS) {
            groundTruth.saveReview(new GroundTruthMatchReview(mediaAssetId,
                    GroundTruthReviewStatus.IN_PROGRESS, clock.instant()));
        }
    }

    private MediaAsset requireAsset(UUID id) {
        return assets.findById(id).orElseThrow(() -> new MediaAssetNotFoundException(id));
    }

    private static void validateWindow(MediaAsset asset, long timestamp, long start, long end, String note) {
        if (timestamp < start || start < 0 || end <= start) {
            throw new IllegalArgumentException("event timestamp must be inside a valid non-negative window");
        }
        if (asset.getDurationMs() != null && end > asset.getDurationMs()) {
            throw new IllegalArgumentException("ground truth window exceeds media duration");
        }
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("note must be at most 2000 characters");
        }
    }

    private static GroundTruthMetrics calculate(GroundTruthReviewStatus status,
                                                List<GroundTruthEvent> expected,
                                                List<CandidateEvent> detected) {
        boolean sufficient = status == GroundTruthReviewStatus.COMPLETED
                && expected.size() >= MINIMUM_EVENTS_FOR_EVALUATION;
        HashSet<UUID> matchedDetected = new HashSet<>();
        int truePositive = 0;
        for (GroundTruthEvent truth : expected) {
            CandidateEvent match = detected.stream()
                    .filter(candidate -> !matchedDetected.contains(candidate.id()))
                    .filter(candidate -> candidate.eventType() == truth.eventType())
                    .filter(candidate -> Math.abs(candidate.triggerTimestampMs() - truth.timestampMs())
                            <= EVENT_MATCH_TOLERANCE_MS)
                    .min(java.util.Comparator.comparingLong(candidate ->
                            Math.abs(candidate.triggerTimestampMs() - truth.timestampMs())))
                    .orElse(null);
            if (match != null) {
                matchedDetected.add(match.id());
                truePositive++;
            }
        }
        int falsePositive = detected.size() - matchedDetected.size();
        int falseNegative = expected.size() - truePositive;
        String message = sufficient ? "Evaluation threshold met"
                : "Complete the match review and annotate at least "
                + MINIMUM_EVENTS_FOR_EVALUATION + " ground-truth events to enable evaluation metrics";
        return new GroundTruthMetrics(sufficient, expected.size(), detected.size(),
                truePositive, falsePositive, falseNegative, message);
    }
}
