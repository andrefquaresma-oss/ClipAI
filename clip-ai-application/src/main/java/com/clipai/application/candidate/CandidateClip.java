package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;

import java.util.List;
import java.util.UUID;

public record CandidateClip(UUID mediaAssetId, UUID candidateId, FootballEventType eventType,
                            CandidateClipCategory category, long startTimeMs, long endTimeMs,
                            String storageKey, List<UUID> sourceCandidateIds, String mergeReason) {
    public CandidateClip {
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }
}
