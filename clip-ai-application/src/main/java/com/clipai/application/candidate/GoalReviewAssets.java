package com.clipai.application.candidate;

import java.util.List;

public record GoalReviewAssets(List<GoalReviewFrame> frames, List<GoalReviewReplay> replays) {
    public GoalReviewAssets {
        frames = List.copyOf(frames);
        replays = List.copyOf(replays);
    }

    public static GoalReviewAssets empty() {
        return new GoalReviewAssets(List.of(), List.of());
    }
}
