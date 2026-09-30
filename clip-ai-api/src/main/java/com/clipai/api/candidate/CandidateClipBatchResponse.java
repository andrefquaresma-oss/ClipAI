package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipBatch;
import com.clipai.application.candidate.CandidateClipBatchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CandidateClipBatchResponse(UUID batchId, UUID mediaAssetId, CandidateClipBatchStatus status,
                                         int totalCandidates, int completedCandidates,
                                         List<CandidateClipBatchItemResponse> clips,
                                         Instant createdAt, Instant updatedAt, String failureReason) {
    public CandidateClipBatchResponse {
        clips = List.copyOf(clips);
    }

    static CandidateClipBatchResponse from(CandidateClipBatch batch) {
        return new CandidateClipBatchResponse(batch.batchId(), batch.mediaAssetId(), batch.status(),
                batch.totalCandidates(), batch.completedCandidates(),
                batch.clips().stream()
                        .map(item -> CandidateClipBatchItemResponse.from(batch.mediaAssetId(), item))
                        .toList(),
                batch.createdAt(), batch.updatedAt(), batch.failureReason());
    }
}
