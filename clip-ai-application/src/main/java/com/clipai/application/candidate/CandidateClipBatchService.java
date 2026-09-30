package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;

public final class CandidateClipBatchService {
    private static final Logger log = LoggerFactory.getLogger(CandidateClipBatchService.class);

    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final CandidateClipService clips;
    private final Clock clock;
    private final Map<UUID, BatchState> latestByMediaAsset = new LinkedHashMap<>();

    public CandidateClipBatchService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                                     CandidateClipService clips, Clock clock) {
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.clips = clips;
        this.clock = clock;
    }

    public synchronized CandidateClipBatch start(UUID mediaAssetId) {
        return start(mediaAssetId, null);
    }

    public synchronized CandidateClipBatch start(UUID mediaAssetId, List<UUID> selectedCandidateIds) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() != MediaAssetStatus.COMPLETED) {
            throw new CandidateClipExportConflictException("Media asset must be completed before clip export");
        }
        BatchState current = latestByMediaAsset.get(mediaAssetId);
        if (current != null && current.snapshot().status() == CandidateClipBatchStatus.PROCESSING) {
            throw new CandidateClipExportConflictException("Clip generation is already processing");
        }

        Instant now = clock.instant();
        List<CandidateEvent> allCandidates = candidates.findByMediaAssetId(mediaAssetId);
        Set<UUID> explicitSelection = selectedCandidateIds == null ? Set.of()
                : new LinkedHashSet<>(selectedCandidateIds);
        if (selectedCandidateIds != null && (selectedCandidateIds.isEmpty()
                || selectedCandidateIds.size() > 100 || explicitSelection.size() != selectedCandidateIds.size())) {
            throw new CandidateClipExportConflictException(
                    "Select between 1 and 100 distinct candidate IDs for clip generation");
        }
        List<CandidateClipBatchItem> items = allCandidates.stream()
                .filter(candidate -> selectedCandidateIds == null
                        ? candidate.status() == CandidateEventStatus.DETECTED
                        : explicitSelection.contains(candidate.id()))
                .map(CandidateClipBatchService::initialItem)
                .toList();
        if (selectedCandidateIds != null && items.size() != explicitSelection.size()) {
            throw new CandidateClipExportConflictException(
                    "One or more selected candidates do not belong to this media asset");
        }
        BatchState batch = new BatchState(UUID.randomUUID(), mediaAssetId, items, now);
        batch.setExplicitSelection(selectedCandidateIds != null);
        latestByMediaAsset.put(mediaAssetId, batch);
        if (batch.pendingCandidateIds().isEmpty()) {
            batch.complete(now);
        }
        log.info("Candidate clip batch started mediaAssetId={} batchId={} candidates={}",
                mediaAssetId, batch.batchId, items.size());
        return batch.snapshot();
    }

    public CandidateClipBatch getLatest(UUID mediaAssetId) {
        mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        BatchState batch;
        synchronized (this) {
            batch = latestByMediaAsset.get(mediaAssetId);
        }
        if (batch == null) {
            throw new CandidateClipBatchNotFoundException(mediaAssetId);
        }
        return batch.snapshot();
    }

    public void generate(UUID mediaAssetId, UUID batchId) {
        BatchState batch = findBatch(mediaAssetId, batchId);
        for (CandidateClipBatchItem item : batch.pendingItems()) {
            try {
                CandidateClipExportResult result = clips.exportWithResult(mediaAssetId,
                        item.candidateId(), false, batch.isExplicitSelection());
                CandidateClip clip = result.clip();
                CandidateClipGenerationStatus status = result.reused()
                        ? CandidateClipGenerationStatus.REUSED : CandidateClipGenerationStatus.GENERATED;
                batch.finish(item.candidateId(), status, clip.storageKey(), null, clock.instant());
            } catch (RuntimeException exception) {
                log.error("Candidate clip generation failed mediaAssetId={} candidateId={}",
                        mediaAssetId, item.candidateId(), exception);
                batch.finish(item.candidateId(), CandidateClipGenerationStatus.FAILED, null,
                        "Clip generation failed", clock.instant());
            }
        }
        batch.complete(clock.instant());
        log.info("Candidate clip batch completed mediaAssetId={} batchId={} status={} generated={}",
                mediaAssetId, batchId, batch.snapshot().status(), batch.snapshot().completedCandidates());
    }

    public void failScheduling(UUID mediaAssetId, UUID batchId) {
        BatchState batch = findBatch(mediaAssetId, batchId);
        batch.failScheduling(clock.instant());
    }

    private synchronized BatchState findBatch(UUID mediaAssetId, UUID batchId) {
        BatchState batch = latestByMediaAsset.get(mediaAssetId);
        if (batch == null || !batch.batchId.equals(batchId)) {
            throw new CandidateClipBatchNotFoundException(mediaAssetId);
        }
        return batch;
    }

    private static CandidateClipBatchItem initialItem(CandidateEvent candidate) {
        return new CandidateClipBatchItem(candidate.id(), candidate.eventType(), candidate.score(),
                candidate.startTimeMs(), candidate.endTimeMs(), candidate.endTimeMs() - candidate.startTimeMs(),
                CandidateClipGenerationStatus.PENDING, null, null,
                candidate.sourceCandidateIds(), candidate.mergeReason());
    }

    private static final class BatchState {
        private final UUID batchId;
        private final UUID mediaAssetId;
        private final Instant createdAt;
        private final Map<UUID, CandidateClipBatchItem> items = new LinkedHashMap<>();
        private CandidateClipBatchStatus status;
        private Instant updatedAt;
        private String failureReason;
        private boolean explicitSelection;

        private BatchState(UUID batchId, UUID mediaAssetId, List<CandidateClipBatchItem> items, Instant createdAt) {
            this.batchId = batchId;
            this.mediaAssetId = mediaAssetId;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.status = CandidateClipBatchStatus.PROCESSING;
            items.forEach(item -> this.items.put(item.candidateId(), item));
        }

        private synchronized List<UUID> pendingCandidateIds() {
            return items.values().stream()
                    .filter(item -> item.generationStatus() == CandidateClipGenerationStatus.PENDING)
                    .map(CandidateClipBatchItem::candidateId)
                    .toList();
        }

        private synchronized void setExplicitSelection(boolean value) {
            explicitSelection = value;
        }

        private synchronized boolean isExplicitSelection() {
            return explicitSelection;
        }

        private synchronized List<CandidateClipBatchItem> pendingItems() {
            return items.values().stream()
                    .filter(item -> item.generationStatus() == CandidateClipGenerationStatus.PENDING)
                    .toList();
        }

        private synchronized void finish(UUID candidateId, CandidateClipGenerationStatus generationStatus,
                                         String storageKey, String itemFailureReason, Instant now) {
            CandidateClipBatchItem current = items.get(candidateId);
            if (current != null && current.generationStatus() == CandidateClipGenerationStatus.PENDING) {
                items.put(candidateId, new CandidateClipBatchItem(current.candidateId(), current.eventType(),
                        current.score(), current.startTimeMs(), current.endTimeMs(), current.durationMs(),
                        generationStatus, storageKey, itemFailureReason,
                        current.sourceCandidateIds(), current.mergeReason()));
                updatedAt = now;
            }
        }

        private synchronized void complete(Instant now) {
            boolean pending = items.values().stream()
                    .anyMatch(item -> item.generationStatus() == CandidateClipGenerationStatus.PENDING);
            if (!pending && status != CandidateClipBatchStatus.FAILED) {
                boolean failed = items.values().stream()
                        .anyMatch(item -> item.generationStatus() == CandidateClipGenerationStatus.FAILED);
                status = failed ? CandidateClipBatchStatus.COMPLETED_WITH_FAILURES
                        : CandidateClipBatchStatus.COMPLETED;
                updatedAt = now;
            }
        }

        private synchronized void failScheduling(Instant now) {
            items.replaceAll((candidateId, item) -> item.generationStatus() == CandidateClipGenerationStatus.PENDING
                    ? new CandidateClipBatchItem(item.candidateId(), item.eventType(), item.score(),
                            item.startTimeMs(), item.endTimeMs(), item.durationMs(),
                            CandidateClipGenerationStatus.FAILED, null, "Clip generation could not be scheduled",
                            item.sourceCandidateIds(), item.mergeReason())
                    : item);
            status = CandidateClipBatchStatus.FAILED;
            failureReason = "Clip generation could not be scheduled";
            updatedAt = now;
        }

        private synchronized CandidateClipBatch snapshot() {
            long completed = items.values().stream()
                    .filter(item -> item.generationStatus() != CandidateClipGenerationStatus.PENDING)
                    .count();
            return new CandidateClipBatch(batchId, mediaAssetId, status, items.size(), (int) completed,
                    new ArrayList<>(items.values()), createdAt, updatedAt, failureReason);
        }
    }
}
