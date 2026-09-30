package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CandidateContextSignalDetector {
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SCORE_PAIR = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[-–—:]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern SEASON_GOAL_REFERENCE = Pattern.compile(
            "\\b(?:first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|"
                    + "primero|segundo|tercero|tercer|cuarto|quinto|premier|premiere|"
                    + "deuxieme|troisieme|quatrieme|cinquieme|primo|secondo|terzo|"
                    + "erste|zweite|dritte)\\s+(?:goal|gol|but|golo|rete|tor)\\s+"
                    + "(?:of|this|da|de|della|der)\\s+(?:the\\s+)?"
                    + "(?:season|temporada|saison|stagione)\\b");
    private static final Pattern SCORE_REPORT = Pattern.compile(
            "\\b(?:the score was|the scoreline|final score|el marcador era|resultado final|"
                    + "le score etait|score final|o placar era|il punteggio era|"
                    + "der spielstand war|endstand)\\s+\\d{1,2}\\s+\\d{1,2}\\b");
    private static final Map<String, List<String>> CURRENT_SCORING_PHRASES = Map.of(
            "pt", List.of("marcou o gol", "faz o gol", "e rede", "abre o placar", "empata"),
            "es", List.of("anota", "marca el gol", "transforma", "abre el marcador", "iguala el marcador"),
            "en", List.of("scores", "finds the net", "puts it away", "opens the scoring", "equalizes"),
            "fr", List.of("vient de marquer", "marque le but", "c est au fond", "egalise", "ouvre le score"),
            "it", List.of("ha segnato", "segna", "in rete", "pareggia"),
            "de", List.of("trifft", "macht das tor", "gleicht aus", "eroffnet den spielstand"));
    private static final long CONTEXT_BRIDGE_GAP_MS = 3000;
    private static final long CONTEXT_SIGNAL_DEDUPLICATION_MS = 4000;
    private final CandidateDetectionQualitySettings settings;

    public CandidateContextSignalDetector(CandidateDetectionQualitySettings settings) {
        this.settings = settings;
    }

    public List<CandidateSignalObservation> detect(Transcript transcript) {
        List<TranscriptSegment> segments = transcript.getSegments().stream()
                .sorted(Comparator.comparingLong(TranscriptSegment::startTimeMs)
                        .thenComparingInt(TranscriptSegment::sequence))
                .toList();
        String language = transcript.getLanguage().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        List<CandidateSignalObservation> observations = new ArrayList<>();
        Map<CandidateSignalType, Long> lastContextSignalMs = new HashMap<>();
        ScoreState previousScore = null;
        for (int index = 0; index < segments.size(); index++) {
            TranscriptSegment segment = segments.get(index);
            String text = localContext(segments, index);
            List<GoalContextClassifier.ContextEvidence> goalContexts = GoalContextClassifier.classify(text);
            if (!goalContexts.isEmpty()) {
                List<CandidateSignal> contextSignals = goalContexts.stream()
                        .map(context -> new CandidateSignal(context.type(), FootballEventType.GOAL,
                                context.confidence(), segment.startTimeMs(), context.evidence()))
                        .toList();
                observations.add(new CandidateSignalObservation(segment.startTimeMs(), contextSignals));
            }
            ScoreState currentScore = scoreState(segment.text());
            if (currentScore != null && currentScore.isPlausible()) {
                String normalizedScoreContext = TranscriptTextNormalizer.normalize(segment.text());
                boolean replayScore = settings.replayPhrases(language).stream()
                        .map(TranscriptTextNormalizer::normalize)
                        .anyMatch(phrase -> containsPhrase(text, phrase));
                boolean retrospectiveScore = SCORE_REPORT.matcher(
                        normalizedScoreContext.replaceAll("(?<=\\d)(?=\\d)", " ")).find()
                        || settings.retrospectivePhrases(language).stream()
                        .map(TranscriptTextNormalizer::normalize)
                        .anyMatch(phrase -> containsPhrase(normalizedScoreContext, phrase));
                boolean correctionScore = goalContexts.stream()
                        .anyMatch(context -> context.type() == CandidateSignalType.CORRECTION_CONTEXT);
                boolean historicalScore = replayScore || retrospectiveScore || correctionScore
                        || goalContexts.stream().anyMatch(context ->
                        context.type() == CandidateSignalType.HISTORICAL_REFERENCE
                                || context.type() == CandidateSignalType.NEGATION_CONTEXT);
                List<CandidateSignal> scoreSignals = new ArrayList<>();
                scoreSignals.add(new CandidateSignal(CandidateSignalType.SCORE_STATE, null,
                        0.72, segment.startTimeMs(),
                        "Transcript explicitly mentions score " + currentScore.display()));
                if (!historicalScore && previousScore != null
                        && currentScore.isSingleGoalAfter(previousScore)) {
                    scoreSignals.add(new CandidateSignal(CandidateSignalType.SCORE_STATE_TRANSITION, null,
                            0.82, segment.startTimeMs(),
                            "Transcript score changed from " + previousScore.display()
                                    + " to " + currentScore.display()));
                }
                observations.add(new CandidateSignalObservation(segment.startTimeMs(), scoreSignals));
                if (!replayScore && !retrospectiveScore && !correctionScore
                        && !goalContexts.stream().anyMatch(context ->
                        context.type() == CandidateSignalType.NEGATION_CONTEXT
                                || context.type() == CandidateSignalType.HISTORICAL_REFERENCE)) {
                    previousScore = currentScore;
                }
            }
            addPhraseSignal(observations, text, settings.replayPhrases(language),
                    CandidateSignalType.REPLAY_CONTEXT, 0.84, segment.startTimeMs(),
                    language, lastContextSignalMs);
            addPhraseSignal(observations, text, settings.retrospectivePhrases(language),
                    CandidateSignalType.RETROSPECTIVE_CONTEXT, 0.80, segment.startTimeMs(),
                    language, lastContextSignalMs);
            addPhraseSignal(observations, text, settings.buildupPhrases(language),
                    CandidateSignalType.ATTACK_BUILDUP, 0.70, segment.startTimeMs(),
                    language, lastContextSignalMs);
            addPhraseSignal(observations, text, settings.restartPhrases(language),
                    CandidateSignalType.RESTART_CONTEXT, 0.91, segment.startTimeMs(),
                    language, lastContextSignalMs);
            addPhraseSignal(observations, text, settings.injuryPhrases(language),
                    CandidateSignalType.INJURY_CONTEXT, 0.86, segment.startTimeMs(),
                    language, lastContextSignalMs);
        }
        addSpeechRateSignals(observations, segments);
        return List.copyOf(observations);
    }

    private static ScoreState scoreState(String text) {
        Matcher matcher = SCORE_PAIR.matcher(text);
        ScoreState match = null;
        while (matcher.find()) {
            ScoreState next = new ScoreState(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
            if (match != null && !match.equals(next)) {
                return null;
            }
            match = next;
        }
        return match;
    }

    private static void addPhraseSignal(List<CandidateSignalObservation> observations, String text,
                                        List<String> phrases, CandidateSignalType type,
                                        double confidence, long timestampMs, String language,
                                        Map<CandidateSignalType, Long> lastSignalTimes) {
        List<String> matches = new ArrayList<>(phrases.stream()
                .map(TranscriptTextNormalizer::normalize)
                .filter(phrase -> containsPhrase(text, phrase))
                .distinct()
                .toList());
        if (type == CandidateSignalType.RETROSPECTIVE_CONTEXT
                && (SEASON_GOAL_REFERENCE.matcher(text).find() || SCORE_REPORT.matcher(text).find())) {
            matches.add("season or score-statistic reference");
        }
        if (!matches.isEmpty()) {
            Long previous = lastSignalTimes.get(type);
            if (previous != null && timestampMs - previous < CONTEXT_SIGNAL_DEDUPLICATION_MS) {
                return;
            }
            if (type == CandidateSignalType.RETROSPECTIVE_CONTEXT
                    && hasCurrentScoringAction(text, language)) {
                confidence = 0.20;
            }
            CandidateSignal signal = new CandidateSignal(type, null,
                    Math.min(0.96, confidence + Math.min(0.08, 0.02 * (matches.size() - 1))),
                    timestampMs, "Context phrase or structure match: " + String.join(", ", matches));
            observations.add(new CandidateSignalObservation(timestampMs, List.of(signal)));
            lastSignalTimes.put(type, timestampMs);
        }
    }

    private static String localContext(List<TranscriptSegment> segments, int centerIndex) {
        TranscriptSegment center = segments.get(centerIndex);
        long start = Math.max(0, center.startTimeMs() - CONTEXT_BRIDGE_GAP_MS);
        long end = center.endTimeMs() > Long.MAX_VALUE - CONTEXT_BRIDGE_GAP_MS
                ? Long.MAX_VALUE : center.endTimeMs() + CONTEXT_BRIDGE_GAP_MS;
        StringBuilder context = new StringBuilder();
        for (int index = Math.max(0, centerIndex - 2);
             index <= Math.min(segments.size() - 1, centerIndex + 2); index++) {
            TranscriptSegment adjacent = segments.get(index);
            if (adjacent.endTimeMs() < start || adjacent.startTimeMs() > end) {
                continue;
            }
            if (!context.isEmpty()) {
                context.append(' ');
            }
            context.append(TranscriptTextNormalizer.normalize(adjacent.text()));
        }
        return context.toString().trim();
    }

    private static boolean hasCurrentScoringAction(String text, String language) {
        return CURRENT_SCORING_PHRASES.getOrDefault(language, List.of()).stream()
                .map(TranscriptTextNormalizer::normalize)
                .anyMatch(phrase -> containsPhrase(text, phrase));
    }

    private void addSpeechRateSignals(List<CandidateSignalObservation> observations,
                                      List<TranscriptSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }
        long[] times = new long[segments.size()];
        int[] wordCounts = new int[segments.size()];
        long[] prefix = new long[segments.size() + 1];
        for (int index = 0; index < segments.size(); index++) {
            times[index] = segments.get(index).startTimeMs();
            wordCounts[index] = wordCount(segments.get(index).text());
            prefix[index + 1] = prefix[index] + wordCounts[index];
        }

        long lastSignalMs = Long.MIN_VALUE;
        for (int index = 0; index < times.length; index++) {
            long timestampMs = times[index];
            long currentStart = Math.max(0, timestampMs - settings.speechRateWindowMs());
            long baselineStart = Math.max(0, currentStart - settings.speechRateWindowMs());
            int currentWords = rangeCount(times, prefix, currentStart, timestampMs);
            int baselineWords = rangeCount(times, prefix, baselineStart, currentStart - 1);
            double rateRatio = (currentWords + 1.0) / (baselineWords + 1.0);
            if (baselineWords == 0 && timestampMs < 2 * settings.speechRateWindowMs()) {
                continue;
            }
            if (currentWords < settings.minimumWordsPerWindow()
                    || rateRatio < settings.speechRateSpikeMultiplier()
                    || (lastSignalMs != Long.MIN_VALUE
                    && timestampMs - lastSignalMs < settings.speechRateWindowMs() / 2)) {
                continue;
            }
            double confidence = Math.min(0.92, 0.58
                    + (rateRatio - settings.speechRateSpikeMultiplier()) * 0.12);
            CandidateSignal signal = new CandidateSignal(CandidateSignalType.SPEECH_RATE_SPIKE,
                    null, confidence, timestampMs,
                    "Transcript contained " + currentWords + " words in "
                            + settings.speechRateWindowMs() + " ms, "
                            + String.format(Locale.ROOT, "%.2f", rateRatio)
                            + "x the preceding-window rate");
            observations.add(new CandidateSignalObservation(timestampMs, List.of(signal)));
            lastSignalMs = timestampMs;
        }
    }

    private static int rangeCount(long[] times, long[] prefix, long startInclusive, long endInclusive) {
        if (endInclusive < startInclusive) {
            return 0;
        }
        int first = lowerBound(times, startInclusive);
        int afterLast = upperBound(times, endInclusive);
        return Math.toIntExact(prefix[afterLast] - prefix[first]);
    }

    private static int lowerBound(long[] values, long target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int upperBound(long[] values, long target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] <= target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int wordCount(String text) {
        return (int) WORD_PATTERN
                .matcher(text)
                .results()
                .count();
    }

    private record ScoreState(int home, int away) {
        boolean isPlausible() {
            return home >= 0 && away >= 0 && home <= 20 && away <= 20;
        }

        boolean isSingleGoalAfter(ScoreState previous) {
            return home == previous.home + 1 && away == previous.away
                    || home == previous.home && away == previous.away + 1;
        }

        String display() {
            return home + "-" + away;
        }
    }

    private static boolean containsPhrase(String normalizedText, String normalizedPhrase) {
        return !normalizedPhrase.isBlank()
                && (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }
}
