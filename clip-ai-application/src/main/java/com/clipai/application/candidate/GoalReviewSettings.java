package com.clipai.application.candidate;

public record GoalReviewSettings(int frameCount, long frameSpacingMs, long replayLookAheadMs,
                                 long replayPrePaddingMs, long replayPostPaddingMs, int maximumReplays) {
    public GoalReviewSettings {
        if (frameCount < 1 || frameCount > 12) {
            throw new IllegalArgumentException("frameCount must be between 1 and 12");
        }
        if (frameSpacingMs <= 0 || replayLookAheadMs <= 0 || replayPrePaddingMs < 0
                || replayPostPaddingMs <= 0) {
            throw new IllegalArgumentException("goal review time settings are invalid");
        }
        if (maximumReplays < 1 || maximumReplays > 5) {
            throw new IllegalArgumentException("maximumReplays must be between 1 and 5");
        }
    }
}
