package com.clipai.api.candidate;

import java.util.List;
import java.util.UUID;

public record CandidateClipBatchRequest(List<UUID> candidateIds) {
    public CandidateClipBatchRequest {
        candidateIds = candidateIds == null ? null : List.copyOf(candidateIds);
    }
}
