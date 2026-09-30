package com.clipai.api.candidate;

import com.clipai.application.candidate.GoalReviewReplay;

public record GoalReviewReplayResponse(int replayNumber, long startTimeMs, long endTimeMs,
                                       long cueTimeMs, String transcriptCue, String downloadUrl) {
    static GoalReviewReplayResponse from(GoalReviewReplay replay, String downloadUrl) {
        return new GoalReviewReplayResponse(replay.replayNumber(), replay.startTimeMs(), replay.endTimeMs(),
                replay.cueTimeMs(), replay.transcriptCue(), downloadUrl);
    }
}
