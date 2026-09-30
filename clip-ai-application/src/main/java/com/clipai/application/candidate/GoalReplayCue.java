package com.clipai.application.candidate;

public record GoalReplayCue(long startTimeMs, long endTimeMs, long cueTimeMs, String transcriptCue) {
    public GoalReplayCue {
        if (startTimeMs < 0 || endTimeMs <= startTimeMs || cueTimeMs < startTimeMs
                || cueTimeMs > endTimeMs || transcriptCue == null || transcriptCue.isBlank()) {
            throw new IllegalArgumentException("goal replay cue is invalid");
        }
    }
}
