package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClip;
import com.clipai.application.candidate.CandidateClipCategory;
import com.clipai.domain.candidate.FootballEventType;

import java.util.List;
import java.util.UUID;

public record CandidateClipResponse(UUID mediaAssetId, UUID candidateId, FootballEventType eventType,
                                    CandidateClipCategory category, long startTimeMs, long endTimeMs,
                                    String storageKey, String downloadUrl, GoalReviewAssetsResponse reviewAssets,
                                    List<UUID> sourceCandidateIds, String mergeReason) {
    public CandidateClipResponse {
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }

    static CandidateClipResponse from(CandidateClip clip, String downloadUrl,
                                      GoalReviewAssetsResponse reviewAssets) {
        return new CandidateClipResponse(clip.mediaAssetId(), clip.candidateId(), clip.eventType(),
                clip.category(), clip.startTimeMs(), clip.endTimeMs(), clip.storageKey(), downloadUrl,
                reviewAssets, clip.sourceCandidateIds(), clip.mergeReason());
    }
}
