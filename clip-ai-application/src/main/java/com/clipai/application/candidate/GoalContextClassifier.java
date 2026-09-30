package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class GoalContextClassifier {
    private static final Pattern NEGATED_SCORING = Pattern.compile(
            "\\b(?:not|never|wasn t|isn t|hasn t|hadn t|didn t|no)\\b"
                    + ".{0,45}\\b(?:score|scored|scoring|goal|net|count)\\b"
                    + "|\\b(?:goal|scored|scoring|net)\\b.{0,35}"
                    + "\\b(?:not|never|wasn t|isn t|hasn t|hadn t|didn t|disallowed|overturned|ruled out)\\b"
                    + "|\\b(?:disallowed|overturned|ruled out|chalked off|waved off)\\b.{0,35}"
                    + "\\b(?:goal|scoring)\\b");
    private static final Pattern CORRECTION = Pattern.compile(
            "\\b(?:corrected|correction|correcting|revised|reversal)\\b"
                    + ".{0,70}\\b(?:goal|score|scorer|attribution|credited)\\b"
                    + "|\\b(?:goal|score|scorer|attribution|credited)\\b.{0,70}"
                    + "\\b(?:corrected|correction|correcting|revised|reversal)\\b");
    private static final Pattern PAST_SCORING = Pattern.compile(
            "\\b(?:had|has)\\s+(?:already\\s+)?scored\\b"
                    + "|\\bscored\\s+(?:earlier|before|previously)\\b");
    private static final List<String> RETROSPECTIVE_PHRASES = List.of(
            "earlier in the match", "the previous goal", "had scored earlier",
            "as we mentioned earlier", "as mentioned earlier", "that goal came earlier",
            "the score was", "the scoreline", "final score");
    private static final List<String> REPLAY_PHRASES = List.of(
            "in the replay", "the replay shows", "watch the replay", "slow motion");

    private GoalContextClassifier() {
    }

    static List<ContextEvidence> classify(String text) {
        String normalized = TranscriptTextNormalizer.normalize(text);
        List<ContextEvidence> result = new ArrayList<>(4);
        if (NEGATED_SCORING.matcher(normalized).find()) {
            result.add(new ContextEvidence(CandidateSignalType.NEGATION_CONTEXT, 0.94,
                    "NEGATION_CONTEXT; nearby wording negates or overturns a scoring action"));
        }
        if (CORRECTION.matcher(normalized).find()) {
            result.add(new ContextEvidence(CandidateSignalType.CORRECTION_CONTEXT, 0.92,
                    "CORRECTION_CONTEXT; nearby wording corrects goal, score, scorer, or attribution"));
        }
        if (REPLAY_PHRASES.stream().anyMatch(normalized::contains)) {
            result.add(new ContextEvidence(CandidateSignalType.REPLAY_CONTEXT, 0.90,
                    "REPLAY_CONTEXT; nearby wording identifies replay commentary"));
        }
        if (PAST_SCORING.matcher(normalized).find()
                || RETROSPECTIVE_PHRASES.stream().anyMatch(normalized::contains)) {
            result.add(new ContextEvidence(CandidateSignalType.RETROSPECTIVE_CONTEXT, 0.90,
                    "RETROSPECTIVE_CONTEXT; nearby wording refers to a prior event or score"));
            result.add(new ContextEvidence(CandidateSignalType.HISTORICAL_REFERENCE, 0.90,
                    "HISTORICAL_REFERENCE; nearby scoring language is explicitly historical"));
        }
        return List.copyOf(result);
    }

    record ContextEvidence(CandidateSignalType type, double confidence, String evidence) {
    }
}
