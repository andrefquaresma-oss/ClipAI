package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptOutcomeSignalDetectorTest {
    @Test
    void detectsExplicitNegativeOutcomesWithoutCreatingPublicEventTypes() {
        Transcript transcript = transcript("en", List.of("The shot is blocked.",
                "Corner to the home side.", "Play continues."));

        List<CandidateSignalObservation> signals = new TranscriptOutcomeSignalDetector().detect(transcript);

        assertEquals(3, signals.size());
        assertTrue(signals.stream().flatMap(observation -> observation.signals().stream())
                .allMatch(signal -> signal.type() == CandidateSignalType.SHOT_OUTCOME_CONTEXT
                        && signal.eventType() == null));
        assertTrue(signals.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.evidence().startsWith("OUTCOME=BLOCK;")));
        assertTrue(signals.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.evidence().startsWith("OUTCOME=CORNER;")));
        assertTrue(signals.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.evidence().startsWith("OUTCOME=CONTINUED_PLAY;")));
    }

    @Test
    void ignoresUnsupportedLanguagesInsteadOfGuessingAtOutcomeMeaning() {
        Transcript transcript = transcript("zz", List.of("The shot is blocked."));

        assertTrue(new TranscriptOutcomeSignalDetector().detect(transcript).isEmpty());
    }

    private static Transcript transcript(String language, List<String> text) {
        Instant now = Instant.now();
        UUID mediaAssetId = UUID.randomUUID();
        Transcript transcript = Transcript.create(mediaAssetId, language, now);
        transcript.startProcessing(now);
        for (int index = 0; index < text.size(); index++) {
            long start = 1_000L + index * 1_000L;
            transcript.addSegment(TranscriptSegment.create(transcript.getId(), index,
                    start, start + 500, text.get(index)));
        }
        transcript.markCompleted(language, now);
        return transcript;
    }
}
