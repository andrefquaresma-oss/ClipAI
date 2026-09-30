package com.clipai.domain.transcript;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscriptTest {
    @Test
    void preservesMillisecondTimestampsAndRejectsInvalidSegments() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Transcript transcript = Transcript.create(UUID.randomUUID(), "en", now);
        TranscriptSegment segment = TranscriptSegment.create(transcript.getId(), 0, 125, 1_875, "Hello");
        transcript.addSegment(segment);
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 1, 80, 120, "Earlier"));

        assertEquals(80, transcript.getSegments().getFirst().startTimeMs());
        assertEquals(125, transcript.getSegments().getLast().startTimeMs());
        assertThrows(IllegalArgumentException.class,
                () -> TranscriptSegment.create(transcript.getId(), 1, 20, 20, "Invalid"));
    }

    @Test
    void enforcesTranscriptLifecycleTransitions() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Transcript transcript = Transcript.create(UUID.randomUUID(), "en", now);

        assertThrows(IllegalStateException.class, () -> transcript.markCompleted(now.plusSeconds(1)));
        transcript.startProcessing(now.plusSeconds(1));
        transcript.markCompleted(now.plusSeconds(2));

        assertEquals(TranscriptStatus.COMPLETED, transcript.getStatus());
        assertThrows(IllegalStateException.class, () -> transcript.markFailed(now.plusSeconds(3)));
    }
}
