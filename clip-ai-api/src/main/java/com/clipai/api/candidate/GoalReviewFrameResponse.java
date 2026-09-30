package com.clipai.api.candidate;

import com.clipai.application.candidate.GoalReviewFrame;

public record GoalReviewFrameResponse(int frameNumber, long timestampMs, String downloadUrl) {
    static GoalReviewFrameResponse from(GoalReviewFrame frame, String downloadUrl) {
        return new GoalReviewFrameResponse(frame.frameNumber(), frame.timestampMs(), downloadUrl);
    }
}
