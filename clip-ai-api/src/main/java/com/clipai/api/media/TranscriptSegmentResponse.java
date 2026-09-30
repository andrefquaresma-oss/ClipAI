package com.clipai.api.media;

import com.clipai.domain.transcript.TranscriptSegment;

public record TranscriptSegmentResponse(int sequence, long startTimeMs, long endTimeMs, String text) {
    public static TranscriptSegmentResponse from(TranscriptSegment segment) {
        return new TranscriptSegmentResponse(segment.sequence(), segment.startTimeMs(),
                segment.endTimeMs(), segment.text());
    }
}
