package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipBatchItem;
import com.clipai.application.candidate.CandidateClipGenerationStatus;
import com.clipai.domain.candidate.FootballEventType;

import java.util.List;
import java.util.UUID;

public record CandidateClipBatchItemResponse(UUID candidateId, FootballEventType eventType, double score,
                                             long startTimeMs, long endTimeMs, long durationMs,
                                             CandidateClipGenerationStatus generationStatus,
                                             String storageKey, String downloadUrl,
                                             String failureReason, List<UUID> sourceCandidateIds,
                                             String mergeReason) {
    public CandidateClipBatchItemResponse {
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }

    static CandidateClipBatchItemResponse from(UUID mediaAssetId, CandidateClipBatchItem item) {
        String downloadUrl = item.storageKey() == null ? null
                : "/api/media-assets/" + mediaAssetId + "/candidates/" + item.candidateId() + "/clip";
        return new CandidateClipBatchItemResponse(item.candidateId(), item.eventType(), item.score(),
                item.startTimeMs(), item.endTimeMs(), item.durationMs(),
                item.generationStatus(), item.storageKey(), downloadUrl, item.failureReason(),
                item.sourceCandidateIds(), item.mergeReason());
    }
}
