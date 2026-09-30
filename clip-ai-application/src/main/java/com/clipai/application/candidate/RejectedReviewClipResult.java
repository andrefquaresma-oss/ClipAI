package com.clipai.application.candidate;

public record RejectedReviewClipResult(RejectedReviewClipStatus status,
                                       long startTimeMs, long endTimeMs,
                                       long triggerTimestampMs, String failureReason) {
}
