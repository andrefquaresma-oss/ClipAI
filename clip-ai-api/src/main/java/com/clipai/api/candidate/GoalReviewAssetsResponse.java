package com.clipai.api.candidate;

import com.clipai.application.candidate.GoalReviewAssets;

import java.util.List;
import java.util.UUID;

public record GoalReviewAssetsResponse(List<GoalReviewFrameResponse> frames,
                                       List<GoalReviewReplayResponse> replays) {
    public GoalReviewAssetsResponse {
        frames = List.copyOf(frames);
        replays = List.copyOf(replays);
    }

    static GoalReviewAssetsResponse from(UUID mediaAssetId, UUID candidateId, GoalReviewAssets assets) {
        String baseUrl = "/api/media-assets/" + mediaAssetId + "/candidates/" + candidateId + "/clip";
        List<GoalReviewFrameResponse> frames = assets.frames().stream()
                .map(frame -> GoalReviewFrameResponse.from(frame, baseUrl + "/frames/" + frame.frameNumber()))
                .toList();
        List<GoalReviewReplayResponse> replays = assets.replays().stream()
                .map(replay -> GoalReviewReplayResponse.from(replay, baseUrl + "/replays/"
                        + replay.replayNumber()))
                .toList();
        return new GoalReviewAssetsResponse(frames, replays);
    }

    static GoalReviewAssetsResponse empty() {
        return new GoalReviewAssetsResponse(List.of(), List.of());
    }
}
