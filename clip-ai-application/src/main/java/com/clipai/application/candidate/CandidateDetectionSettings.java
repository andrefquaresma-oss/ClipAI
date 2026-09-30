package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record CandidateDetectionSettings(
        long audioWindowMs,
        long audioBaselineWindowMs,
        double audioSpikeThresholdDb,
        double audioMinimumDb,
        long audioSustainedThresholdMs,
        long preEventPaddingMs,
        long postEventPaddingMs,
        long mergeDistanceMs,
        double minimumCandidateScore,
        double singleSignalConfidenceThreshold,
        int maximumCandidates,
        long repeatedPhraseWindowMs,
        int repeatedPhraseMinimumSegments,
        int transcriptEmphasisMinimumLetters,
        double transcriptEmphasisUppercaseRatio,
        double transcriptWeight,
        double audioWeight,
        double signalAgreementBonus,
        Map<String, Map<FootballEventType, List<String>>> lexicon,
        Map<FootballEventType, Double> eventConfidence,
        Map<String, List<String>> ownGoalPhrases) {

    public CandidateDetectionSettings(long audioWindowMs, long audioBaselineWindowMs,
                                      double audioSpikeThresholdDb, double audioMinimumDb,
                                      long audioSustainedThresholdMs, long preEventPaddingMs,
                                      long postEventPaddingMs, long mergeDistanceMs,
                                      double minimumCandidateScore, double singleSignalConfidenceThreshold,
                                      int maximumCandidates, long repeatedPhraseWindowMs,
                                      int repeatedPhraseMinimumSegments, int transcriptEmphasisMinimumLetters,
                                      double transcriptEmphasisUppercaseRatio, double transcriptWeight,
                                      double audioWeight, double signalAgreementBonus,
                                      Map<String, Map<FootballEventType, List<String>>> lexicon,
                                      Map<FootballEventType, Double> eventConfidence) {
        this(audioWindowMs, audioBaselineWindowMs, audioSpikeThresholdDb, audioMinimumDb,
                audioSustainedThresholdMs, preEventPaddingMs, postEventPaddingMs, mergeDistanceMs,
                minimumCandidateScore, singleSignalConfidenceThreshold, maximumCandidates,
                repeatedPhraseWindowMs, repeatedPhraseMinimumSegments, transcriptEmphasisMinimumLetters,
                transcriptEmphasisUppercaseRatio, transcriptWeight, audioWeight, signalAgreementBonus,
                lexicon, eventConfidence, Map.of());
    }

    public CandidateDetectionSettings {
        requirePositive(audioWindowMs, "audioWindowMs");
        requirePositive(audioBaselineWindowMs, "audioBaselineWindowMs");
        if (audioBaselineWindowMs / 2 <= audioWindowMs) {
            throw new IllegalArgumentException("audioBaselineWindowMs must exceed twice audioWindowMs");
        }
        if (audioSpikeThresholdDb <= 0 || !Double.isFinite(audioSpikeThresholdDb)) {
            throw new IllegalArgumentException("audioSpikeThresholdDb must be positive and finite");
        }
        if (!Double.isFinite(audioMinimumDb) || audioMinimumDb >= 0) {
            throw new IllegalArgumentException("audioMinimumDb must be finite and below zero");
        }
        requirePositive(audioSustainedThresholdMs, "audioSustainedThresholdMs");
        requireNonNegative(preEventPaddingMs, "preEventPaddingMs");
        requireNonNegative(postEventPaddingMs, "postEventPaddingMs");
        requireNonNegative(mergeDistanceMs, "mergeDistanceMs");
        requireUnitInterval(minimumCandidateScore, "minimumCandidateScore");
        requireUnitInterval(singleSignalConfidenceThreshold, "singleSignalConfidenceThreshold");
        if (maximumCandidates < 1) {
            throw new IllegalArgumentException("maximumCandidates must be at least 1");
        }
        requirePositive(repeatedPhraseWindowMs, "repeatedPhraseWindowMs");
        if (repeatedPhraseMinimumSegments < 2) {
            throw new IllegalArgumentException("repeatedPhraseMinimumSegments must be at least 2");
        }
        if (transcriptEmphasisMinimumLetters < 1) {
            throw new IllegalArgumentException("transcriptEmphasisMinimumLetters must be at least 1");
        }
        requireUnitInterval(transcriptEmphasisUppercaseRatio, "transcriptEmphasisUppercaseRatio");
        if (!Double.isFinite(transcriptWeight) || transcriptWeight <= 0
                || !Double.isFinite(audioWeight) || audioWeight <= 0) {
            throw new IllegalArgumentException("signal weights must be positive and finite");
        }
        requireUnitInterval(signalAgreementBonus, "signalAgreementBonus");

        Map<String, Map<FootballEventType, List<String>>> normalizedLexicon = new LinkedHashMap<>();
        Objects.requireNonNull(lexicon, "lexicon").forEach((language, events) -> {
            String languageKey = Objects.requireNonNull(language, "language").trim().toLowerCase(Locale.ROOT);
            if (languageKey.isBlank()) {
                throw new IllegalArgumentException("lexicon language must not be blank");
            }
            Map<FootballEventType, List<String>> normalizedEvents = new LinkedHashMap<>();
            Objects.requireNonNull(events, "language dictionary").forEach((eventType, phrases) -> {
                List<String> cleanPhrases = Objects.requireNonNull(phrases, "phrases").stream()
                        .map(phrase -> Objects.requireNonNull(phrase, "phrase").trim())
                        .filter(phrase -> !phrase.isBlank())
                        .distinct()
                        .toList();
                if (!cleanPhrases.isEmpty()) {
                    normalizedEvents.put(Objects.requireNonNull(eventType, "eventType"), cleanPhrases);
                }
            });
            normalizedLexicon.put(languageKey, Map.copyOf(normalizedEvents));
        });
        lexicon = Map.copyOf(normalizedLexicon);

        Map<FootballEventType, Double> normalizedConfidence = new LinkedHashMap<>();
        Objects.requireNonNull(eventConfidence, "eventConfidence").forEach((eventType, confidence) -> {
            requireUnitInterval(Objects.requireNonNull(confidence, "event confidence"), "event confidence");
            normalizedConfidence.put(Objects.requireNonNull(eventType, "eventType"), confidence);
        });
        eventConfidence = Map.copyOf(normalizedConfidence);

        Map<String, List<String>> normalizedOwnGoalPhrases = new LinkedHashMap<>();
        Objects.requireNonNull(ownGoalPhrases, "ownGoalPhrases").forEach((language, phrases) -> {
            String languageKey = Objects.requireNonNull(language, "language").trim().toLowerCase(Locale.ROOT);
            if (languageKey.isBlank()) {
                throw new IllegalArgumentException("own-goal phrase language must not be blank");
            }
            List<String> cleanPhrases = Objects.requireNonNull(phrases, "own-goal phrases").stream()
                    .map(phrase -> Objects.requireNonNull(phrase, "own-goal phrase").trim())
                    .filter(phrase -> !phrase.isBlank())
                    .distinct()
                    .toList();
            normalizedOwnGoalPhrases.put(languageKey, cleanPhrases);
        });
        ownGoalPhrases = Map.copyOf(normalizedOwnGoalPhrases);
    }

    public double confidenceFor(FootballEventType eventType) {
        return eventConfidence.getOrDefault(eventType, 0.72);
    }

    public List<String> ownGoalPhrases(String language) {
        String key = language.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return ownGoalPhrases.getOrDefault(key, List.of());
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireUnitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
