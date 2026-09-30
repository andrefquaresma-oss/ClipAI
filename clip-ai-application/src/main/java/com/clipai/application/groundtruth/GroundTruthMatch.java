package com.clipai.application.groundtruth;

import com.clipai.domain.media.MediaAsset;

import java.util.List;

public record GroundTruthMatch(MediaAsset mediaAsset, GroundTruthReviewStatus reviewStatus,
                               List<GroundTruthEvent> events, GroundTruthMetrics metrics) {
    public GroundTruthMatch {
        events = List.copyOf(events);
    }
}
