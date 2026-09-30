package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;

import java.util.List;
import java.util.UUID;

public record CandidateClipBatchItem(UUID candidateId, FootballEventType eventType, double score,
                                     long startTimeMs, long endTimeMs, long durationMs,
                                     CandidateClipGenerationStatus generationStatus, String storageKey,
                                     String failureReason, List<UUID> sourceCandidateIds, String mergeReason) {
    public CandidateClipBatchItem {
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }
}
