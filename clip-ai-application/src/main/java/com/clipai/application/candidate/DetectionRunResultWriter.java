package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateSignal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DetectionRunResultWriter {
    void persistCompleted(UUID runId, UUID mediaAssetId, List<CandidateSignal> observations,
                          List<CandidateEvent> candidates, Instant completedAt);
}
