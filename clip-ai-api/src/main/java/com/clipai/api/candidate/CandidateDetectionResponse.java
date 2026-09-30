package com.clipai.api.candidate;

import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.media.MediaAsset;

import java.util.UUID;

public record CandidateDetectionResponse(UUID mediaAssetId, CandidateDetectionStatus status,
                                         String failureReason) {
    static CandidateDetectionResponse from(MediaAsset asset) {
        return new CandidateDetectionResponse(asset.getId(), asset.getCandidateDetectionStatus(),
                asset.getCandidateDetectionFailureReason());
    }
}
