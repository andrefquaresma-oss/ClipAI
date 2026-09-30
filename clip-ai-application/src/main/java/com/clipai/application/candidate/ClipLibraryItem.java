package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;

import java.util.UUID;

public record ClipLibraryItem(UUID mediaAssetId, String mediaAssetTitle, UUID candidateId,
                              FootballEventType eventType, CandidateReviewStatus reviewStatus,
                              long startTimeMs, long endTimeMs,
                              String downloadUrl, UUID detectionRunId) {
}
