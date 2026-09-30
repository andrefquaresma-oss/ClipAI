package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalReplayDetectorTest {
    @Test
    void findsOnlyExplicitPostGoalReplayCueAndBuildsBoundedWindow() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID assetId = UUID.randomUUID();
        Transcript transcript = Transcript.create(assetId, "fr", now);
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 0,
                100_000, 101_000, "Goal."));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 1,
                150_000, 151_000, "On peut revoir tout de suite."));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 2,
                152_000, 153_000, "L'Ã©galisation rennaise."));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 3,
                170_000, 171_000, "On revoit l'action au ralenti."));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 4,
                230_000, 231_000, "On revoit le centre."));
        CandidateEvent goal = CandidateEvent.detected(assetId, 80_000, 120_000, 100_000,
                FootballEventType.GOAL, 0.9,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        FootballEventType.GOAL, 0.9, 100_000, "scored")),
                "Goal.", now);
        GoalReviewSettings settings = new GoalReviewSettings(5, 5_000,
                300_000, 8_000, 25_000, 3);

        List<GoalReplayCue> cues = new GoalReplayDetector(settings).detect(transcript, goal);

        assertEquals(1, cues.size());
        assertEquals(142_000, cues.getFirst().startTimeMs());
        assertEquals(175_000, cues.getFirst().endTimeMs());
        assertEquals(150_000, cues.getFirst().cueTimeMs());
    }
}
