package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorage;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;

import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.UUID;

public final class CandidateClipService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final MediaStorage mediaStorage;
    private final ClipStorage clipStorage;
    private final VideoClipper videoClipper;
    private final CandidateReviewRepository reviews;

    public CandidateClipService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                                MediaStorage mediaStorage, ClipStorage clipStorage, VideoClipper videoClipper) {
        this(mediaAssets, candidates, mediaStorage, clipStorage, videoClipper,
                CandidateReviewRepository.none());
    }

    public CandidateClipService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                                MediaStorage mediaStorage, ClipStorage clipStorage, VideoClipper videoClipper,
                                CandidateReviewRepository reviews) {
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.mediaStorage = mediaStorage;
        this.clipStorage = clipStorage;
        this.videoClipper = videoClipper;
        this.reviews = reviews;
    }

    public CandidateClip export(UUID mediaAssetId, UUID candidateId) {
        return exportWithResult(mediaAssetId, candidateId).clip();
    }

    public CandidateClipExportResult exportWithResult(UUID mediaAssetId, UUID candidateId) {
        return exportWithResult(mediaAssetId, candidateId, false);
    }

    public CandidateClipExportResult exportWithResult(UUID mediaAssetId, UUID candidateId,
                                                       boolean regenerate) {
        return exportWithResult(mediaAssetId, candidateId, regenerate, false);
    }

    public CandidateClipExportResult exportWithResult(UUID mediaAssetId, UUID candidateId,
                                                       boolean regenerate, boolean explicitlySelected) {
        ExportTarget target = loadTarget(mediaAssetId, candidateId, true, explicitlySelected);
        var existing = clipStorage.find(mediaAssetId, target.category(), candidateId);
        if (existing.isPresent() && !regenerate && !target.manualBoundary()) {
            return new CandidateClipExportResult(toClip(target, existing.get()), true);
        }

        String sourceKey = target.asset().getLocalStoragePath();
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new CandidateClipExportConflictException("Source video is unavailable for clip export");
        }
        Path sourcePath = mediaStorage.resolve(sourceKey);
        ClipStorageLocation location = clipStorage.prepare(mediaAssetId, target.category(), candidateId);
        Path outputPath = existing.isPresent()
                ? location.path().resolveSibling("." + candidateId + "-" + UUID.randomUUID() + ".mp4")
                : location.path();
        try {
            videoClipper.cut(sourcePath, outputPath,
                    target.startTimeMs(), target.endTimeMs());
            if (existing.isPresent()) {
                try {
                    Files.move(outputPath, location.path(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(outputPath, location.path(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (RuntimeException exception) {
            cleanupFailedExport(existing.isPresent(), location, outputPath, exception);
            throw exception;
        } catch (IOException exception) {
            CandidateClipExportConflictException failure =
                    new CandidateClipExportConflictException("Unable to replace the existing clip", exception);
            cleanupFailedExport(true, location, outputPath, failure);
            throw failure;
        }
        return new CandidateClipExportResult(toClip(target, location), false);
    }

    private void cleanupFailedExport(boolean existingClip, ClipStorageLocation location, Path outputPath,
                                     RuntimeException failure) {
        try {
            Files.deleteIfExists(outputPath);
            if (!existingClip) {
                clipStorage.delete(location);
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public CandidateClip findExported(UUID mediaAssetId, UUID candidateId) {
        ExportTarget target = loadTarget(mediaAssetId, candidateId, false);
        ClipStorageLocation location = clipStorage.find(mediaAssetId, target.category(), candidateId)
                .orElseThrow(() -> new CandidateClipNotFoundException(candidateId));
        return toClip(target, location);
    }

    private ExportTarget loadTarget(UUID mediaAssetId, UUID candidateId, boolean requireExportable) {
        return loadTarget(mediaAssetId, candidateId, requireExportable, false);
    }

    private ExportTarget loadTarget(UUID mediaAssetId, UUID candidateId, boolean requireExportable,
                                    boolean explicitlySelected) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (requireExportable && asset.getStatus() != MediaAssetStatus.COMPLETED) {
            throw new CandidateClipExportConflictException("Media asset must be completed before clip export");
        }
        CandidateEvent event = candidates.findByIdAndMediaAssetId(candidateId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(candidateId, mediaAssetId));
        CandidateReview review = reviews.findByCandidateId(candidateId).orElse(null);
        boolean confirmed = review != null && review.status() == CandidateReviewStatus.CONFIRMED;
        boolean rejectedByReviewer = review != null && review.status() == CandidateReviewStatus.REJECTED;
        if (requireExportable && !explicitlySelected
                && (rejectedByReviewer || (event.status() != CandidateEventStatus.DETECTED && !confirmed))) {
            throw new CandidateClipExportConflictException("Rejected candidates cannot be exported as clips");
        }
        CandidateClipCategory category = CandidateClipCategory.forEventType(event.eventType());
        long startTimeMs = review != null && review.hasManualBoundary()
                ? review.manualStartTimeMs() : event.startTimeMs();
        long endTimeMs = review != null && review.hasManualBoundary()
                ? review.manualEndTimeMs() : event.endTimeMs();
        return new ExportTarget(asset, event, category, startTimeMs, endTimeMs,
                review != null && review.hasManualBoundary());
    }

    private CandidateClip toClip(ExportTarget target, ClipStorageLocation location) {
        return new CandidateClip(target.asset().getId(), target.event().id(), target.event().eventType(),
                target.category(), target.startTimeMs(), target.endTimeMs(),
                location.storageKey(), target.event().sourceCandidateIds(), target.event().mergeReason());
    }

    private record ExportTarget(MediaAsset asset, CandidateEvent event, CandidateClipCategory category,
                                long startTimeMs, long endTimeMs, boolean manualBoundary) {
    }
}
