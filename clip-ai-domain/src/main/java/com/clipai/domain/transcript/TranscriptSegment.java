package com.clipai.domain.transcript;

import java.util.Objects;
import java.util.UUID;

public record TranscriptSegment(UUID id, UUID transcriptId, int sequence,
                               long startTimeMs, long endTimeMs, String text) {
    public TranscriptSegment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transcriptId, "transcriptId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (startTimeMs < 0 || endTimeMs <= startTimeMs) {
            throw new IllegalArgumentException("segment timestamps must be non-negative and end after start");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    public static TranscriptSegment create(UUID transcriptId, int sequence,
                                           long startTimeMs, long endTimeMs, String text) {
        return new TranscriptSegment(UUID.randomUUID(), transcriptId, sequence, startTimeMs, endTimeMs, text);
    }
}
