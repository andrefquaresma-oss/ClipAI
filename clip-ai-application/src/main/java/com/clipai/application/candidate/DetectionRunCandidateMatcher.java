package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DetectionRunCandidateMatcher {
    private static final long MAXIMUM_TIME_DELTA_MS = 15_000;
    private static final double MINIMUM_CONTEXT_SIMILARITY = 0.35;
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+");

    public Result match(List<CandidateEvent> left, List<CandidateEvent> right) {
        List<CandidateEvent> orderedLeft = left.stream()
                .sorted(Comparator.comparingLong(CandidateEvent::triggerTimestampMs)
                        .thenComparing(CandidateEvent::id)).toList();
        List<CandidateEvent> orderedRight = right.stream()
                .sorted(Comparator.comparingLong(CandidateEvent::triggerTimestampMs)
                        .thenComparing(CandidateEvent::id)).toList();
        Set<UUID> pairedRight = new HashSet<>();
        List<DetectionRunCandidateMatch> matches = new ArrayList<>();
        List<CandidateEvent> onlyLeft = new ArrayList<>();
        for (CandidateEvent leftCandidate : orderedLeft) {
            CandidateEvent best = orderedRight.stream()
                    .filter(candidate -> !pairedRight.contains(candidate.id()))
                    .filter(candidate -> eligible(leftCandidate, candidate))
                    .min(Comparator
                            .comparingLong((CandidateEvent candidate) ->
                                    Math.abs(candidate.triggerTimestampMs() - leftCandidate.triggerTimestampMs()))
                            .thenComparing(Comparator.comparingDouble(
                                    (CandidateEvent candidate) -> similarity(
                                            leftCandidate.transcriptContext(), candidate.transcriptContext()))
                                    .reversed())
                            .thenComparingLong(CandidateEvent::triggerTimestampMs)
                            .thenComparing(CandidateEvent::id))
                    .orElse(null);
            if (best == null) {
                onlyLeft.add(leftCandidate);
                continue;
            }
            pairedRight.add(best.id());
            double contextSimilarity = similarity(leftCandidate.transcriptContext(), best.transcriptContext());
            String reason = leftCandidate.eventType() == best.eventType()
                    ? "same event type within " + MAXIMUM_TIME_DELTA_MS + " ms"
                    : "context similarity " + String.format(Locale.ROOT, "%.2f", contextSimilarity)
                            + " within " + MAXIMUM_TIME_DELTA_MS + " ms";
            matches.add(new DetectionRunCandidateMatch(leftCandidate, best, reason,
                    Math.abs(leftCandidate.score() - best.score()) > 0.00005,
                    leftCandidate.eventType() != best.eventType(),
                    leftCandidate.status() != best.status()));
        }
        List<CandidateEvent> onlyRight = orderedRight.stream()
                .filter(candidate -> !pairedRight.contains(candidate.id())).toList();
        return new Result(matches, onlyLeft, onlyRight);
    }

    private static boolean eligible(CandidateEvent left, CandidateEvent right) {
        if (Math.abs(left.triggerTimestampMs() - right.triggerTimestampMs()) > MAXIMUM_TIME_DELTA_MS) {
            return false;
        }
        return left.eventType() == right.eventType()
                || similarity(left.transcriptContext(), right.transcriptContext()) >= MINIMUM_CONTEXT_SIMILARITY;
    }

    private static double similarity(String leftText, String rightText) {
        if (leftText == null || rightText == null || leftText.isBlank() || rightText.isBlank()) {
            return 0;
        }
        Set<String> left = words(leftText);
        Set<String> right = words(rightText);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> words(String text) {
        return WORDS.matcher(text.toLowerCase(Locale.ROOT)).results()
                .map(match -> match.group()).collect(Collectors.toSet());
    }

    public record Result(List<DetectionRunCandidateMatch> matches,
                         List<CandidateEvent> onlyLeft, List<CandidateEvent> onlyRight) {
        public Result {
            matches = List.copyOf(matches);
            onlyLeft = List.copyOf(onlyLeft);
            onlyRight = List.copyOf(onlyRight);
        }
    }
}
