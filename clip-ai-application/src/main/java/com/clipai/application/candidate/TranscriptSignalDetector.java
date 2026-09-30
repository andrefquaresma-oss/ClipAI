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
import java.util.regex.Pattern;

public final class TranscriptSignalDetector {
    private static final int MAXIMUM_MATCHES_PER_EVENT = 3;
    private static final long GOAL_PHRASE_REPEAT_SUPPRESSION_MS = 90_000;
    private static final long PENALTY_GOAL_CONTEXT_MS = 45_000;
    private static final long PENALTY_GOAL_DEDUPLICATION_MS = 90_000;
    private static final Pattern FRENCH_COMEBACK_GOAL = Pattern.compile(
            "\\b(?:remet|remettre|ramene|ramener|relance|relancer)\\b.*\\bdans (?:ce|le) match\\b");
    private static final Pattern SEASON_GOAL_REFERENCE = Pattern.compile(
            "\\b(?:first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|"
                    + "primero|segundo|tercero|tercer|cuarto|quinto|premier|premiere|"
                    + "deuxieme|troisieme|quatrieme|cinquieme|primo|secondo|terzo|"
                    + "erste|zweite|dritte)\\s+(?:goal|gol|but|golo|rete|tor)\\s+"
                    + "(?:of|this|da|de|della|der)\\s+(?:the\\s+)?"
                    + "(?:season|temporada|saison|stagione)\\b");
    private static final Pattern ELONGATED_GOAL_CALL = Pattern.compile("\\bg(?:o){2,}l+\\b");
    private static final Map<String, List<String>> CURRENT_SCORING_PHRASES = Map.of(
            "pt", List.of("marca", "marcou o gol", "faz o gol", "e rede", "abre o placar", "empata"),
            "es", List.of("anota", "marca el gol", "transforma", "abre el marcador", "iguala el marcador"),
            "en", List.of("scores", "finds the net", "puts it away", "opens the scoring", "equalizes"),
            "fr", List.of("vient de marquer", "marque le but", "c est au fond", "egalise",
                    "ouvre le score", "but qu on vient de marquer", "troisieme but"),
            "it", List.of("ha segnato", "segna", "in rete", "pareggia"),
            "de", List.of("trifft", "macht das tor", "gleicht aus", "eroffnet den spielstand"));
    private static final List<String> RETROSPECTIVE_SCORE_PHRASES = List.of(
            "the score was", "the scoreline", "final score", "el marcador era", "resultado final",
            "le score etait", "score final", "o placar era", "il punteggio era",
            "der spielstand war", "endstand");
    private static final Map<String, List<String>> GOAL_KICK_PHRASES = Map.of(
            "pt", List.of("tiro de meta", "pontape de baliza"),
            "es", List.of("saque de puerta", "saque de meta", "saque de porteria"),
            "en", List.of("goal kick"),
            "fr", List.of("coup de pied de but", "renvoi aux six metres", "degagement aux six metres"),
            "it", List.of("rinvio dal fondo", "calcio di rinvio"),
            "de", List.of("abstoß", "abstoß vom tor", "abschlag vom tor"));
    private static final Map<String, List<String>> STRONG_GOAL_PHRASES = Map.of(
            "pt", List.of("golaço", "marcou", "fez o gol", "é rede", "gol para"),
            "es", List.of("golazo", "marca el gol", "anotó", "anota", "transforma", "abre el marcador",
                    "igualó", "gol para"),
            "en", List.of("scores", "scored", "goal scored", "goal for", "goal by", "finds the net",
                    "puts it away", "scores from", "it s gone in", "it has gone in", "he s found the net",
                    "they ve found the net", "into the back of the net", "in the back of the net",
                    "makes it five", "makes it 5", "taps it in", "own goal",
                    "scores an own goal", "turns it into his own net", "deflected into his own net"),
            "fr", List.of("marque le but", "a marqué", "vient de marquer", "inscrit par",
                    "c'est au fond", "au fond des filets", "et c'est but", "égalisation", "égalise",
                    "but pour", "but inscrit par", "but qu'on vient de marquer", "troisième but"),
            "it", List.of("ha segnato", "segna", "in rete", "gol per", "rete per"),
            "de", List.of("trifft", "macht das tor", "gleicht aus", "tor fur", "tor fur"));

    private final CandidateDetectionSettings settings;

    public TranscriptSignalDetector(CandidateDetectionSettings settings) {
        this.settings = settings;
    }

    public List<CandidateSignalObservation> detect(Transcript transcript) {
        List<TranscriptSegment> segments = transcript.getSegments().stream()
                .sorted(Comparator.comparingLong(TranscriptSegment::startTimeMs)
                        .thenComparingInt(TranscriptSegment::sequence))
                .toList();
        String language = transcript.getLanguage().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        Map<String, List<Long>> recentSegmentFingerprints = new HashMap<>();
        Map<String, Long> recentGoalPhrases = new HashMap<>();
        List<CandidateSignalObservation> observations = new ArrayList<>();

        for (int index = 0; index < segments.size(); index++) {
            TranscriptSegment segment = segments.get(index);
            String normalizedText = TranscriptTextNormalizer.normalize(segment.text());
            if (normalizedText.isBlank()) {
                continue;
            }
            String localContext = localContext(segments, index);
            observations.addAll(findLexicalSignals(normalizedText, segment.text(), language, segment.startTimeMs(),
                    recentGoalPhrases, localContext));
            CandidateSignalObservation emphasis = findEmphasis(segment.text(), segment.startTimeMs(), settings);
            if (emphasis != null) {
                observations.add(emphasis);
            }
            CandidateSignalObservation repetition = findRepeatedPhrases(normalizedText, segment.startTimeMs(),
                    recentSegmentFingerprints);
            if (repetition != null) {
                observations.add(repetition);
            }
            if (language.equals("fr")) {
                CandidateSignalObservation comeback = findFrenchComebackGoal(normalizedText,
                        segment.startTimeMs());
                if (comeback != null) {
                    observations.add(comeback);
                }
            }
        }
        observations.addAll(findScoringPenaltySignals(segments, language, observations));
        return List.copyOf(observations);
    }

    private List<CandidateSignalObservation> findLexicalSignals(String text, String originalText,
                                                                 String language, long timestampMs,
                                                                 Map<String, Long> recentGoalPhrases,
                                                                 String localContext) {
        Map<FootballEventType, List<String>> phrases = new HashMap<>();
        Map<FootballEventType, List<String>> languagePhrases = settings.lexicon().get(language);
        if (languagePhrases != null) {
            mergePhrases(phrases, languagePhrases);
        }
        Map<FootballEventType, List<String>> sharedPhrases = settings.lexicon().get("und");
        if (sharedPhrases != null) {
            mergePhrases(phrases, sharedPhrases);
        }
        if (phrases.isEmpty()) {
            settings.lexicon().values().forEach(dictionary -> mergePhrases(phrases, dictionary));
        }

        List<CandidateSignalObservation> observations = new ArrayList<>();
        if (hasGoalKickPhrase(text, language)) {
            CandidateSignal signal = new CandidateSignal(CandidateSignalType.GOAL_KICK_CONTEXT,
                    FootballEventType.GOAL, 0.96, timestampMs,
                    "Typed football expression identifies a goal kick, not a scoring event");
            observations.add(new CandidateSignalObservation(timestampMs, List.of(signal)));
        }
        phrases.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            FootballEventType eventType = entry.getKey();
            List<String> matches = new ArrayList<>(entry.getValue().stream()
                    .filter(phrase -> containsPhrase(text, TranscriptTextNormalizer.normalize(phrase)))
                    .distinct()
                    .sorted()
                    .limit(MAXIMUM_MATCHES_PER_EVENT)
                    .toList());
            if (eventType == FootballEventType.GOAL && ELONGATED_GOAL_CALL.matcher(text).find()) {
                matches.add("elongated goal-call vocalization");
            }
            if (!matches.isEmpty()) {
                if (eventType == FootballEventType.GOAL) {
                    if (hasGoalKickPhrase(text, language)) {
                        return;
                    }
                    if (isNonScoringGoalReference(text)
                            || isRetrospectiveGoalReference(text, language)) {
                        return;
                    }
                    String fingerprint = String.join("|", matches);
                    Long previousTimestamp = recentGoalPhrases.put(fingerprint, timestampMs);
                    if (previousTimestamp != null
                            && timestampMs - previousTimestamp <= GOAL_PHRASE_REPEAT_SUPPRESSION_MS) {
                        return;
                    }
                }
                double confidence = Math.min(0.98, settings.confidenceFor(eventType) + 0.07 * (matches.size() - 1));
                if (eventType == FootballEventType.GOAL
                        && !hasStrongGoalPhrase(text, language)
                        && !isCelebratoryBareGoal(originalText, text)) {
                    confidence = Math.min(confidence, 0.68);
                }
                List<CandidateSignal> signals = new ArrayList<>(List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                                eventType, confidence, timestampMs,
                                "Football phrase match: " + String.join(", ", matches)),
                        new CandidateSignal(transcriptTypeFor(eventType), eventType, confidence,
                                timestampMs, "Event-specific transcript phrase match")));
                if (eventType == FootballEventType.GOAL) {
                    GoalContextClassifier.classify(localContext).forEach(context ->
                            signals.add(new CandidateSignal(context.type(), FootballEventType.GOAL,
                                    context.confidence(), timestampMs, context.evidence())));
                    List<String> ownGoalMatches = settings.ownGoalPhrases(language).stream()
                            .map(TranscriptTextNormalizer::normalize)
                            .filter(phrase -> containsPhrase(text, phrase))
                            .distinct()
                            .toList();
                    if (!ownGoalMatches.isEmpty()) {
                        signals.add(new CandidateSignal(CandidateSignalType.OWN_GOAL,
                                FootballEventType.GOAL, confidence, timestampMs,
                                "OWN_GOAL; configured own-goal phrase match: "
                                        + String.join(", ", ownGoalMatches)));
                    }
                }
                observations.add(new CandidateSignalObservation(timestampMs, signals));
            }
        });
        return observations;
    }

    private static boolean hasGoalKickPhrase(String text, String language) {
        List<String> phrases = GOAL_KICK_PHRASES.getOrDefault(language,
                GOAL_KICK_PHRASES.values().stream().flatMap(List::stream).toList());
        return phrases.stream()
                .map(TranscriptTextNormalizer::normalize)
                .anyMatch(phrase -> containsPhrase(text, phrase));
    }

    private static boolean hasStrongGoalPhrase(String text, String language) {
        return STRONG_GOAL_PHRASES.getOrDefault(language, List.of()).stream()
                .map(TranscriptTextNormalizer::normalize)
                .anyMatch(phrase -> containsPhrase(text, phrase))
                || ELONGATED_GOAL_CALL.matcher(text).find();
    }

    private static String localContext(List<TranscriptSegment> segments, int centerIndex) {
        TranscriptSegment center = segments.get(centerIndex);
        long start = Math.max(0, center.startTimeMs() - 3_000);
        long end = center.endTimeMs() > Long.MAX_VALUE - 3_000
                ? Long.MAX_VALUE : center.endTimeMs() + 3_000;
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

    private static boolean isCelebratoryBareGoal(String originalText, String normalizedText) {
        if (!normalizedText.matches("(?:goal|gol|but|golo|rete|tor)")) {
            return false;
        }
        return originalText.strip().matches("(?iu)(?:goal|gol|but|golo|rete|tor)\\s*!+");
    }

    private static void mergePhrases(Map<FootballEventType, List<String>> target,
                                     Map<FootballEventType, List<String>> additions) {
        additions.forEach((eventType, phrases) ->
                target.computeIfAbsent(eventType, ignored -> new ArrayList<>()).addAll(phrases));
    }

    private static CandidateSignalObservation findEmphasis(String original, long timestampMs,
                                                            CandidateDetectionSettings settings) {
        long letters = original.codePoints().filter(Character::isLetter).count();
        long uppercase = original.codePoints().filter(Character::isUpperCase).count();
        long exclamations = original.chars().filter(character -> character == '!').count();
        double uppercaseRatio = letters == 0 ? 0 : (double) uppercase / letters;
        if (exclamations == 0 && (letters < settings.transcriptEmphasisMinimumLetters()
                || uppercaseRatio < settings.transcriptEmphasisUppercaseRatio())) {
            return null;
        }
        double confidence = clamp(0.48 + Math.min(exclamations, 4) * 0.08
                + Math.max(0, uppercaseRatio - settings.transcriptEmphasisUppercaseRatio()) * 0.6, 0.98);
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                FootballEventType.COMMENTATOR_REACTION, confidence, timestampMs,
                "Commentary emphasis detected (exclamation marks=" + exclamations
                        + ", uppercase ratio=" + String.format(Locale.ROOT, "%.2f", uppercaseRatio) + ")");
        return new CandidateSignalObservation(timestampMs, List.of(signal));
    }

    private CandidateSignalObservation findRepeatedPhrases(String normalized, long timestampMs,
                                                            Map<String, List<Long>> recentFingerprints) {
        List<String> tokens = List.of(normalized.split("\\s+"));
        long repeatedWords = 0;
        for (int index = 1; index < tokens.size(); index++) {
            if (tokens.get(index).equals(tokens.get(index - 1))) {
                repeatedWords++;
            }
        }
        List<Long> matchingTimes = recentFingerprints.computeIfAbsent(normalized, ignored -> new ArrayList<>());
        matchingTimes.removeIf(time -> timestampMs - time > settings.repeatedPhraseWindowMs());
        matchingTimes.add(timestampMs);
        int repeatCount = Math.max(Math.toIntExact(Math.min(Integer.MAX_VALUE, repeatedWords + 1)), matchingTimes.size());
        if (tokens.size() < 2 || repeatCount < settings.repeatedPhraseMinimumSegments()) {
            return null;
        }
        double confidence = Math.min(0.97, 0.58 + 0.08 * (repeatCount - 2));
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_REPETITION,
                FootballEventType.COMMENTATOR_REACTION, confidence, timestampMs,
                "Commentary phrase repeated " + repeatCount + " times within the configured window");
        return new CandidateSignalObservation(timestampMs, List.of(signal));
    }

    private static CandidateSignalObservation findFrenchComebackGoal(String normalizedText, long timestampMs) {
        if (!FRENCH_COMEBACK_GOAL.matcher(normalizedText).find()
                || normalizedText.contains("occasion") || normalizedText.contains("chance")
                || normalizedText.contains("essaie") || normalizedText.contains("tente")) {
            return null;
        }
        List<CandidateSignal> signals = List.of(
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        FootballEventType.GOAL, 0.84, timestampMs,
                        "Football phrase match: French commentary says a team is being put back into the match"),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                        FootballEventType.GOAL, 0.84, timestampMs,
                        "French comeback phrase suggests a score change"));
        return new CandidateSignalObservation(timestampMs, signals);
    }

    private List<CandidateSignalObservation> findScoringPenaltySignals(
            List<TranscriptSegment> segments, String language,
            List<CandidateSignalObservation> existingObservations) {
        Map<FootballEventType, List<String>> languagePhrases = settings.lexicon().get(language);
        if (languagePhrases == null || languagePhrases.get(FootballEventType.PENALTY) == null) {
            return List.of();
        }
        List<String> penaltyPhrases = languagePhrases.get(FootballEventType.PENALTY).stream()
                .map(TranscriptTextNormalizer::normalize)
                .filter(phrase -> !phrase.isBlank())
                .toList();
        List<CandidateSignalObservation> outcomes = new ArrayList<>();
        long lastConvertedPenaltyMs = Long.MIN_VALUE;
        for (TranscriptSegment segment : segments) {
            String normalizedPenalty = TranscriptTextNormalizer.normalize(segment.text());
            if (penaltyPhrases.stream().noneMatch(phrase -> containsPhrase(normalizedPenalty, phrase))
                    || (lastConvertedPenaltyMs != Long.MIN_VALUE
                    && segment.startTimeMs() - lastConvertedPenaltyMs <= PENALTY_GOAL_DEDUPLICATION_MS)
                    || hasNearbyDirectGoalSignal(existingObservations, segment.startTimeMs())) {
                continue;
            }
            long outcomeEndMs = segment.startTimeMs() > Long.MAX_VALUE - PENALTY_GOAL_CONTEXT_MS
                    ? Long.MAX_VALUE : segment.startTimeMs() + PENALTY_GOAL_CONTEXT_MS;
            List<String> followingText = segments.stream()
                    .filter(following -> following.startTimeMs() >= segment.startTimeMs()
                            && following.startTimeMs() <= outcomeEndMs)
                    .map(following -> TranscriptTextNormalizer.normalize(following.text()))
                    .toList();
            boolean attempt = followingText.stream().anyMatch(TranscriptSignalDetector::containsPenaltyAttempt);
            boolean missed = followingText.stream().anyMatch(TranscriptSignalDetector::containsMissedPenalty);
            boolean scoreChange = followingText.stream().anyMatch(TranscriptSignalDetector::containsScoreChange);
            if (!attempt || missed || !scoreChange) {
                continue;
            }
            List<CandidateSignal> signals = List.of(
                    new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                            FootballEventType.GOAL, 0.90, segment.startTimeMs(),
                            "Penalty attempt followed by score-change commentary; outcome is inferred and requires review"),
                    new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                            FootballEventType.GOAL, 0.90, segment.startTimeMs(),
                            "Penalty attempt followed by score-change commentary"));
            outcomes.add(new CandidateSignalObservation(segment.startTimeMs(), signals));
            lastConvertedPenaltyMs = segment.startTimeMs();
        }
        return List.copyOf(outcomes);
    }

    private static boolean containsPenaltyAttempt(String text) {
        return text.contains("cest parti") || text.contains("c est parti")
                || text.contains("frappe") || text.contains("tire")
                || text.contains("s elance") || text.contains("tire le penalty");
    }

    private static boolean containsMissedPenalty(String text) {
        return text.contains("rate le penalty") || text.contains("penalty manque")
                || text.contains("manque son penalty") || text.contains("arrete le penalty")
                || text.contains("gardien arrete") || text.contains("repousse le penalty")
                || text.contains("pas de but");
    }

    private static boolean containsScoreChange(String text) {
        if (text.contains("match alle") || text.contains("de la saison") || text.contains("avait marque")) {
            return false;
        }
        return text.contains("mener") || text.contains("prend l avantage")
                || text.contains("egalisation") || text.contains("ouvre le score")
                || text.contains("cest au fond") || text.contains("au fond des filets")
                || text.contains("vient de marquer") || text.contains("but qu on vient de marquer")
                || text.contains("but pour");
    }

    private static boolean hasNearbyDirectGoalSignal(List<CandidateSignalObservation> observations,
                                                      long penaltyTimestampMs) {
        return observations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.eventType() == FootballEventType.GOAL
                        && signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && Math.abs(signal.timestampMs() - penaltyTimestampMs) <= PENALTY_GOAL_DEDUPLICATION_MS);
    }

    private static boolean isNonScoringGoalReference(String normalizedText) {
        return normalizedText.contains("avant cette egalisation")
                || normalizedText.contains("avant l egalisation")
                || normalizedText.contains("juste apres le but")
                && !normalizedText.contains("but qu on vient de marquer")
                && !normalizedText.contains("but que l on vient de marquer")
                || normalizedText.contains("revoir le but")
                || normalizedText.contains("au match alle")
                || normalizedText.contains("but de la saison")
                || normalizedText.contains("face au but")
                || normalizedText.contains("pres du but")
                || normalizedText.contains("a cote du but");
    }

    private static boolean isRetrospectiveGoalReference(String normalizedText, String language) {
        boolean statisticalReference = SEASON_GOAL_REFERENCE.matcher(normalizedText).find()
                || RETROSPECTIVE_SCORE_PHRASES.stream().anyMatch(normalizedText::contains)
                || normalizedText.contains("goal number")
                || normalizedText.contains("gol numero")
                || normalizedText.contains("but numero");
        if (!statisticalReference) {
            return false;
        }
        return CURRENT_SCORING_PHRASES.getOrDefault(language, List.of()).stream()
                .map(TranscriptTextNormalizer::normalize)
                .noneMatch(phrase -> containsPhrase(normalizedText, phrase));
    }

    private static boolean containsPhrase(String normalizedText, String normalizedPhrase) {
        if (normalizedPhrase.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static CandidateSignalType transcriptTypeFor(FootballEventType eventType) {
        return switch (eventType) {
            case GOAL -> CandidateSignalType.TRANSCRIPT_GOAL;
            case SHOT, NEAR_MISS -> CandidateSignalType.TRANSCRIPT_SHOT;
            case PENALTY, MISSED_PENALTY -> CandidateSignalType.TRANSCRIPT_PENALTY;
            case YELLOW_CARD, RED_CARD -> CandidateSignalType.TRANSCRIPT_CARD;
            default -> CandidateSignalType.TRANSCRIPT_EVENT;
        };
    }

    private static double clamp(double value, double maximum) {
        return Math.max(0, Math.min(maximum, value));
    }
}
