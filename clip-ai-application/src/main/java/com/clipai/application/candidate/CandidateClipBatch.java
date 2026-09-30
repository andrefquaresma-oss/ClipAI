package com.clipai.application.candidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CandidateClipBatch(UUID batchId, UUID mediaAssetId, CandidateClipBatchStatus status,
                                 int totalCandidates, int completedCandidates, List<CandidateClipBatchItem> clips,
                                 Instant createdAt, Instant updatedAt, String failureReason) {
    public CandidateClipBatch {
        clips = List.copyOf(clips);
    }
}
