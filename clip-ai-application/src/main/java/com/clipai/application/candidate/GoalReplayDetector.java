package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GoalReplayDetector {
    private static final long MINIMUM_REPLAY_DELAY_MS = 5_000;
    private static final long MAXIMUM_CONTEXT_GAP_MS = 12_000;
    private static final long REPLAY_DEDUPLICATION_MS = 25_000;

    private final GoalReviewSettings settings;

    public GoalReplayDetector(GoalReviewSettings settings) {
        this.settings = settings;
    }

    public List<GoalReplayCue> detect(Transcript transcript, CandidateEvent goal) {
        if (goal.eventType() != FootballEventType.GOAL) {
            return List.of();
        }
        long searchUntil = addSaturated(goal.triggerTimestampMs(), settings.replayLookAheadMs());
        long transcriptDuration = transcript.getSegments().stream()
                .mapToLong(TranscriptSegment::endTimeMs)
                .max().orElse(0);
        List<TranscriptSegment> segments = transcript.getSegments().stream()
                .sorted(Comparator.comparingLong(TranscriptSegment::startTimeMs)
                        .thenComparingInt(TranscriptSegment::sequence))
                .toList();
        List<GoalReplayCue> cues = new ArrayList<>();
        for (int index = 0; index < segments.size() && cues.size() < settings.maximumReplays(); index++) {
            TranscriptSegment segment = segments.get(index);
            long cueTime = segment.startTimeMs();
            if (cueTime < addSaturated(goal.triggerTimestampMs(), MINIMUM_REPLAY_DELAY_MS)
                    || cueTime > searchUntil) {
                continue;
            }
            StringBuilder context = new StringBuilder(segment.text());
            long contextEnd = segment.endTimeMs();
            for (int next = index + 1; next < segments.size(); next++) {
                TranscriptSegment following = segments.get(next);
                if (following.startTimeMs() - contextEnd > MAXIMUM_CONTEXT_GAP_MS
                        || following.startTimeMs() - cueTime > MAXIMUM_CONTEXT_GAP_MS) {
                    break;
                }
                context.append(' ').append(following.text());
                contextEnd = following.endTimeMs();
                if (following.startTimeMs() - cueTime >= MAXIMUM_CONTEXT_GAP_MS / 2) {
                    break;
                }
            }
            String normalized = TranscriptTextNormalizer.normalize(context.toString());
            if (!containsReplayCue(normalized) || !referencesGoal(normalized)) {
                continue;
            }
            if (!cues.isEmpty()
                    && cueTime - cues.getLast().cueTimeMs() <= REPLAY_DEDUPLICATION_MS) {
                continue;
            }
            long startTime = Math.max(0, cueTime - settings.replayPrePaddingMs());
            long endTime = Math.min(transcriptDuration,
                    addSaturated(cueTime, settings.replayPostPaddingMs()));
            if (endTime > startTime) {
                cues.add(new GoalReplayCue(startTime, endTime, cueTime,
                        segment.text().length() > 400 ? segment.text().substring(0, 400) : segment.text()));
            }
        }
        return List.copyOf(cues);
    }

    private static boolean containsReplayCue(String text) {
        return text.contains("revoir") || text.contains("revoy") || text.contains("revoit")
                || text.contains("ralenti") || text.contains("replay") || text.contains("repetition")
                || text.contains("images de");
    }

    private static boolean referencesGoal(String text) {
        return text.contains("but") || text.contains("goal") || text.contains("gol")
                || text.contains("egalisation") || text.contains("action")
                || text.contains("penalty") || text.contains("penalti");
    }

    private static long addSaturated(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
