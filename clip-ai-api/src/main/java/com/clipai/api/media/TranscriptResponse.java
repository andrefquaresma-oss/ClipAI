package com.clipai.api.media;

import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptStatus;

import java.util.List;
import java.util.UUID;

public record TranscriptResponse(UUID id, UUID mediaAssetId, String language, TranscriptStatus status,
                                 String failureReason, List<TranscriptSegmentResponse> segments) {
    public TranscriptResponse {
        segments = List.copyOf(segments);
    }

    public static TranscriptResponse from(Transcript transcript) {
        return new TranscriptResponse(transcript.getId(), transcript.getMediaAssetId(), transcript.getLanguage(),
                transcript.getStatus(), transcript.getFailureReason(),
                transcript.getSegments().stream().map(TranscriptSegmentResponse::from).toList());
    }
}
