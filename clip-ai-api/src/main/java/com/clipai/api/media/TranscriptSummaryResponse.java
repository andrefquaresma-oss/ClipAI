package com.clipai.api.media;

import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptStatus;

import java.util.UUID;

public record TranscriptSummaryResponse(UUID id, TranscriptStatus status, String language,
                                       int segmentCount, String failureReason) {
    public static TranscriptSummaryResponse from(Transcript transcript) {
        return new TranscriptSummaryResponse(transcript.getId(), transcript.getStatus(), transcript.getLanguage(),
                transcript.getSegments().size(), transcript.getFailureReason());
    }
}
