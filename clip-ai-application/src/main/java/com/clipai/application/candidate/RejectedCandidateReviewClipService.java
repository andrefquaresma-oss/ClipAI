package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.RejectedReviewClipStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.media.MediaAsset;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class RejectedCandidateReviewClipService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final CandidateReviewRepository reviews;
    private final MediaStorage mediaStorage;
    private final RejectedReviewClipStorage clips;
    private final VideoClipper videoClipper;
    private final Executor executor;
    private final long defaultWindowDurationMs;
    private final Map<Key, State> states = new ConcurrentHashMap<>();

    public RejectedCandidateReviewClipService(MediaAssetRepository mediaAssets,
                                              CandidateEventRepository candidates,
                                              CandidateReviewRepository reviews,
                                              MediaStorage mediaStorage,
                                              RejectedReviewClipStorage clips,
                                              VideoClipper videoClipper,
                                              Executor executor,
                                              long defaultWindowDurationMs) {
        if (defaultWindowDurationMs < 1) {
            throw new IllegalArgumentException("defaultWindowDurationMs must be positive");
        }
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.reviews = reviews;
        this.mediaStorage = mediaStorage;
        this.clips = clips;
        this.videoClipper = videoClipper;
        this.executor = executor;
        this.defaultWindowDurationMs = defaultWindowDurationMs;
    }

    public RejectedReviewClipResult get(UUID mediaAssetId, UUID candidateId) {
        Target target = loadTarget(mediaAssetId, candidateId, false);
        Key key = new Key(mediaAssetId, candidateId);
        if (isHumanRejected(candidateId)) {
            State current = states.get(key);
            if (current != null && current.status() == RejectedReviewClipStatus.PROCESSING) {
                return result(current.status(), target, null);
            }
            if (clips.find(mediaAssetId, candidateId).isPresent()) {
                State failed = new State(RejectedReviewClipStatus.FAILED,
                        "Human rejection is saved, but temporary clip cleanup failed.", false);
                states.put(key, failed);
                return result(failed.status(), target, failed.failureReason());
            }
            states.put(key, new State(RejectedReviewClipStatus.DELETED, null, false));
            return result(RejectedReviewClipStatus.DELETED, target, null);
        }
        State current = states.get(key);
        if (current != null && current.status() == RejectedReviewClipStatus.PROCESSING) {
            return result(current.status(), target, current.failureReason());
        }
        if (current != null && current.status() == RejectedReviewClipStatus.FAILED) {
            return result(current.status(), target, current.failureReason());
        }
        if (clips.find(mediaAssetId, candidateId).isPresent()) {
            states.put(key, new State(RejectedReviewClipStatus.READY, null, false));
            return result(RejectedReviewClipStatus.READY, target, null);
        }
        return result(current == null ? RejectedReviewClipStatus.ABSENT : current.status(),
                target, current == null ? null : current.failureReason());
    }

    public RejectedReviewClipResult request(UUID mediaAssetId, UUID candidateId) {
        Target target = loadTarget(mediaAssetId, candidateId, true);
        Key key = new Key(mediaAssetId, candidateId);
        synchronized (states) {
            State current = states.get(key);
            if (clips.find(mediaAssetId, candidateId).isPresent()) {
                states.put(key, new State(RejectedReviewClipStatus.READY, null, false));
                return result(RejectedReviewClipStatus.READY, target, null);
            }
            if (current != null && current.status() == RejectedReviewClipStatus.PROCESSING) {
                return result(current.status(), target, null);
            }
            states.put(key, new State(RejectedReviewClipStatus.PROCESSING, null, false));
            try {
                executor.execute(() -> generate(key, target));
            } catch (RejectedExecutionException exception) {
                states.put(key, new State(RejectedReviewClipStatus.FAILED,
                        "Review clip worker is busy; try again.", false));
                throw exception;
            }
        }
        State state = states.get(key);
        return result(state.status(), target, state.failureReason());
    }

    public Optional<ClipStorageLocation> find(UUID mediaAssetId, UUID candidateId) {
        loadTarget(mediaAssetId, candidateId, false);
        if (isHumanRejected(candidateId)) {
            return Optional.empty();
        }
        return clips.find(mediaAssetId, candidateId);
    }

    public boolean deleteAfterConfirmation(UUID mediaAssetId, UUID candidateId) {
        loadTarget(mediaAssetId, candidateId, false);
        if (!isHumanRejected(candidateId)) {
            return false;
        }
        Key key = new Key(mediaAssetId, candidateId);
        synchronized (states) {
            State state = states.get(key);
            if (state != null && state.status() == RejectedReviewClipStatus.PROCESSING) {
                states.put(key, new State(RejectedReviewClipStatus.PROCESSING, null, true));
                return true;
            }
            try {
                clips.delete(mediaAssetId, candidateId);
                states.put(key, new State(RejectedReviewClipStatus.DELETED, null, false));
                return true;
            } catch (RuntimeException exception) {
                states.put(key, new State(RejectedReviewClipStatus.FAILED,
                        "Human rejection is saved, but temporary clip cleanup failed.", false));
                return false;
            }
        }
    }

    private void generate(Key key, Target target) {
        ClipStorageLocation output = null;
        try {
            output = clips.prepare(key.mediaAssetId(), key.candidateId());
            videoClipper.cut(mediaStorage.resolve(target.asset().getLocalStoragePath()),
                    output.path(), target.startTimeMs(), target.endTimeMs());
            synchronized (states) {
                State current = states.get(key);
                boolean confirmed = reviews.findByCandidateId(key.candidateId())
                        .map(review -> review.status() == CandidateReviewStatus.REJECTED).orElse(false);
                if ((current != null && current.deleteAfterCompletion()) || confirmed) {
                    clips.delete(key.mediaAssetId(), key.candidateId());
                    states.put(key, new State(RejectedReviewClipStatus.DELETED, null, false));
                } else {
                    states.put(key, new State(RejectedReviewClipStatus.READY, null, false));
                }
            }
        } catch (RuntimeException exception) {
            try {
                if (output != null) {
                    clips.delete(key.mediaAssetId(), key.candidateId());
                }
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            states.put(key, new State(RejectedReviewClipStatus.FAILED,
                    "Review clip generation failed; try again.", false));
        }
    }

    private Target loadTarget(UUID mediaAssetId, UUID candidateId, boolean requirePending) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        CandidateEvent candidate = candidates.findByIdAndMediaAssetId(candidateId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(candidateId, mediaAssetId));
        if (candidate.status() != CandidateEventStatus.REJECTED) {
            throw new CandidateClipExportConflictException("Only system-rejected candidates have review clips");
        }
        CandidateReview review = reviews.findByCandidateId(candidateId).orElse(null);
        if (requirePending && review != null && review.status() == CandidateReviewStatus.REJECTED) {
            throw new CandidateClipExportConflictException("Human-confirmed rejections do not need review clips");
        }
        Long mediaDurationMs = asset.getDurationMs() != null && asset.getDurationMs() > 0
                ? asset.getDurationMs() : null;
        long start = review != null && review.hasManualBoundary()
                ? review.manualStartTimeMs() : candidate.startTimeMs();
        long end = review != null && review.hasManualBoundary()
                ? review.manualEndTimeMs() : candidate.endTimeMs();
        if (end - start > defaultWindowDurationMs) {
            long half = defaultWindowDurationMs / 2;
            start = Math.max(0, candidate.triggerTimestampMs() - half);
            end = safeAdd(start, defaultWindowDurationMs);
            if (mediaDurationMs != null && end > mediaDurationMs) {
                end = mediaDurationMs;
                start = Math.max(0, end - defaultWindowDurationMs);
            }
        } else {
            if (mediaDurationMs != null) {
                start = Math.min(start, mediaDurationMs);
                end = Math.min(end, mediaDurationMs);
            }
        }
        if (end <= start || candidate.triggerTimestampMs() < start || candidate.triggerTimestampMs() > end) {
            long half = defaultWindowDurationMs / 2;
            start = Math.max(0, candidate.triggerTimestampMs() - half);
            end = safeAdd(candidate.triggerTimestampMs(), half);
            if (mediaDurationMs != null && end > mediaDurationMs) {
                end = mediaDurationMs;
                start = Math.max(0, end - defaultWindowDurationMs);
            }
            if (end <= start || candidate.triggerTimestampMs() > end) {
                throw new CandidateClipExportConflictException("Candidate timestamp is outside media duration");
            }
        }
        if (asset.getLocalStoragePath() == null || asset.getLocalStoragePath().isBlank()) {
            throw new CandidateClipExportConflictException("Source video is unavailable for review clip creation");
        }
        return new Target(asset, start, end, candidate.triggerTimestampMs());
    }

    private static long safeAdd(long value, long increment) {
        return Long.MAX_VALUE - value < increment ? Long.MAX_VALUE : value + increment;
    }

    private boolean isHumanRejected(UUID candidateId) {
        return reviews.findByCandidateId(candidateId)
                .map(review -> review.status() == CandidateReviewStatus.REJECTED)
                .orElse(false);
    }

    private static RejectedReviewClipResult result(RejectedReviewClipStatus status, Target target, String failure) {
        return new RejectedReviewClipResult(status, target.startTimeMs(), target.endTimeMs(),
                target.triggerTimestampMs(), failure);
    }

    private record Key(UUID mediaAssetId, UUID candidateId) {
    }

    private record State(RejectedReviewClipStatus status, String failureReason, boolean deleteAfterCompletion) {
    }

    private record Target(MediaAsset asset, long startTimeMs, long endTimeMs, long triggerTimestampMs) {
    }
}
