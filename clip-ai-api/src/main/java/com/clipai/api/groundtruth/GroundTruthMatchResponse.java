package com.clipai.api.groundtruth;

import com.clipai.application.groundtruth.GroundTruthMatch;
import com.clipai.application.groundtruth.GroundTruthMetrics;
import com.clipai.application.groundtruth.GroundTruthReviewStatus;

import java.util.List;
import java.util.UUID;

public record GroundTruthMatchResponse(UUID mediaAssetId, String title, GroundTruthReviewStatus reviewStatus,
                                      List<GroundTruthEventResponse> events, GroundTruthMetrics metrics) {
    static GroundTruthMatchResponse from(GroundTruthMatch match) {
        return new GroundTruthMatchResponse(match.mediaAsset().getId(), match.mediaAsset().getTitle(),
                match.reviewStatus(), match.events().stream().map(GroundTruthEventResponse::from).toList(),
                match.metrics());
    }
}
