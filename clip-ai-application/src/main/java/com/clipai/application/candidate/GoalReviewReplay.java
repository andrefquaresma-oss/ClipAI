package com.clipai.application.candidate;

public record GoalReviewReplay(int replayNumber, long startTimeMs, long endTimeMs, long cueTimeMs,
                               String transcriptCue, String storageKey) {
}
