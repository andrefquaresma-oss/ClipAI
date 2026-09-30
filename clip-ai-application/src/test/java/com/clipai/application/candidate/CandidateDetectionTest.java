package com.clipai.application.candidate;

import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyAnalysis;
import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyWindow;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateDetectionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void detectsLanguageAwareKeywordsAndCommentaryEmphasis() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("pt", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 5_000, 6_000, "É GOLAÇO!!!")
        });

        var observations = new TranscriptSignalDetector(settings).detect(transcript);
        var signals = observations.stream().flatMap(observation -> observation.signals().stream()).toList();

        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                && signal.eventType() == FootballEventType.GOAL));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_EMPHASIS));
        assertTrue(signals.stream().allMatch(signal -> signal.timestampMs() == 5_000));
    }

    @Test
    void exposesScoreComponentsThatReconcileWithTheCandidateScore() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "Scores the goal!")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 10_000, "Scores the goal"))),
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_SPIKE, null, 0.90, 10_000,
                        "Local intensity increase"))));

        CandidateEvent candidate = new CandidateEventAssembler(settings).assemble(
                transcript.getMediaAssetId(), transcript, observations, 30_000, NOW).getFirst();

        assertEquals(candidate.score(), candidate.scoreContributions().stream()
                .mapToDouble(com.clipai.domain.candidate.CandidateScoreComponent::contribution)
                .sum(), 1e-9);
        assertTrue(candidate.scoreContributions().stream().anyMatch(component ->
                component.type() == com.clipai.domain.candidate.CandidateScoreComponentType.AUDIO_REACTION
                        && component.evidenceConfidence() < 0.90));
    }

    @Test
    void recognizesRepeatedCommentaryPhrases() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "what a chance"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000, "what a chance")
        });

        var observations = new TranscriptSignalDetector(settings).detect(transcript);

        assertTrue(observations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_REPETITION));
    }

    @Test
    void normalizesAccentsWhenMatchingConfiguredPhrases() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("pt", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "Pênalti para o time")
        });

        var observations = new TranscriptSignalDetector(settings).detect(transcript);

        assertTrue(observations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.PENALTY));
    }

    @Test
    void requiresActionSpecificWordingInsteadOfPenaltySpotContext() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000,
                        "El balón llega al punto de penalti."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000,
                        "Penalti señalado para el Barcelona.")
        });

        var penalties = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.PENALTY)
                .toList();

        assertEquals(List.of(3_000L), penalties.stream().map(CandidateSignal::timestampMs).toList());
    }

    @Test
    void recognizesTypedGoalKickExpressionsAcrossSupportedLanguagesWithoutCreatingGoals() {
        CandidateDetectionSettings settings = settings();
        Map<String, String> goalKicks = Map.of(
                "en", "Goal kick for Barcelona.",
                "pt", "Tiro de meta para o Barcelona.",
                "es", "Saque de puerta para el Barcelona.",
                "fr", "Coup de pied de but pour Barcelone.",
                "it", "Rinvio dal fondo per il Barcellona.",
                "de", "Abstoß für Barcelona.");

        for (Map.Entry<String, String> entry : goalKicks.entrySet()) {
            Transcript transcript = transcript(entry.getKey(), new TranscriptSegment[] {
                    TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, entry.getValue())
            });
            List<CandidateSignal> signals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                    .flatMap(observation -> observation.signals().stream()).toList();

            assertTrue(signals.stream().anyMatch(signal ->
                    signal.type() == CandidateSignalType.GOAL_KICK_CONTEXT), entry.getKey());
            assertFalse(signals.stream().anyMatch(signal ->
                    signal.type() == CandidateSignalType.TRANSCRIPT_GOAL), entry.getKey());
        }
    }

    @Test
    void treatsBareGoalAsWeakButScoringLanguageAndCelebratoryGoalAsStrong() {
        CandidateDetectionSettings settings = settings();
        Transcript bare = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "Goal")
        });
        Transcript scored = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "Goal scored for Barcelona.")
        });
        Transcript celebration = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "GOAL!!!")
        });

        double bareConfidence = new TranscriptSignalDetector(settings).detect(bare).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                .mapToDouble(CandidateSignal::confidence).max().orElseThrow();
        double scoredConfidence = new TranscriptSignalDetector(settings).detect(scored).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                .mapToDouble(CandidateSignal::confidence).max().orElseThrow();
        double celebrationConfidence = new TranscriptSignalDetector(settings).detect(celebration).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                .mapToDouble(CandidateSignal::confidence).max().orElseThrow();

        assertEquals(0.68, bareConfidence);
        assertTrue(scoredConfidence >= 0.88);
        assertTrue(celebrationConfidence >= 0.88);
    }

    @Test
    void discoversGoalFromOrderedBuildUpShotAudioAndCommentaryWithoutGoalKeyword() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "Crosses into the box."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 20_000, 21_000, "He strikes it cleanly."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 22_000, 23_000, "What a moment!")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.ATTACK_BUILDUP, null, 0.75, 10_000,
                        "Commentary phrase match: crosses into the box"))),
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 0.84, 20_000,
                        "Football phrase match: shot"))),
                new CandidateSignalObservation(21_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_SPIKE, null, 0.86, 21_000,
                        "Independent local reaction"))),
                new CandidateSignalObservation(22_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EMPHASIS, FootballEventType.COMMENTATOR_REACTION,
                        0.82, 22_000, "Commentary emphasis detected"))));

        List<CandidateSignalObservation> inferred = new CandidateGoalDiscovery(quality).discover(observations);
        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, combine(observations, inferred), 90_000, NOW);
        CandidateEvent goal = candidates.stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL).findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertEquals(20_000, goal.triggerTimestampMs());
        assertTrue(goal.score() < 1.0);
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_GOAL
                        && signal.evidence().contains("shot")));
    }

    @Test
    void doesNotInferGoalFromGenericExcitementOrFromGoalKickContext() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<CandidateSignalObservation> excitementOnly = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_SPIKE, null, 0.95, 10_000, "Audio spike"))),
                new CandidateSignalObservation(11_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EMPHASIS, FootballEventType.COMMENTATOR_REACTION,
                        0.92, 11_000, "Commentator excitement"))));
        List<CandidateSignalObservation> goalKickNearShot = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.ATTACK_BUILDUP, null, 0.75, 10_000, "Buildup"))),
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 0.84, 20_000, "Shot"))),
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.GOAL_KICK_CONTEXT, FootballEventType.GOAL,
                        0.96, 20_000, "Goal kick is a restart"))),
                new CandidateSignalObservation(21_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_SPIKE, null, 0.86, 21_000, "Audio spike"))),
                new CandidateSignalObservation(22_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EMPHASIS, FootballEventType.COMMENTATOR_REACTION,
                        0.82, 22_000, "Commentary emphasis"))));

        assertTrue(new CandidateGoalDiscovery(quality).discover(excitementOnly).isEmpty());
        assertTrue(new CandidateGoalDiscovery(quality).discover(goalKickNearShot).isEmpty());
    }

    @Test
    void recognizesAnElongatedSpanishGoalCallAsStrongTranscriptEvidence() {
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 54_000, 58_000, "GOOOOOOOL!!!")
        });

        List<CandidateSignal> signals = new TranscriptSignalDetector(settings()).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .toList();

        CandidateSignal goal = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                .findFirst().orElseThrow();
        assertTrue(goal.confidence() >= 0.88);
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_EMPHASIS));
    }

    @Test
    void detectsAdeyemiGoalFromNoisyTranscriptAndSustainedAudioWithoutUsingNameOrTimestampRules() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 2_644_180, 2_646_180,
                        "Con 0-1 en el marcador."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_255_180, 3_257_180,
                        "De llemi se va a quedar solo, de llemi."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 3_267_180, 3_271_180,
                        "La pierna derecha resolviendo el ataque, poniendo ya tierra de por medio."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 3_271_180, 3_276_180,
                        "Tranquilidad para el equipo culé que ganan a 0-2 antes del descanso.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));
        observations.addAll(new AudioExcitementDetector(settings, quality).detect(sustainedGoalCallAudio()));
        List<CandidateSignalObservation> goalHypotheses = new CandidateGoalDiscovery(quality).discover(observations);
        observations.addAll(goalHypotheses);

        long actualGoalTimeMs = 3_257_000;
        CandidateEvent goal = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 3_541_013, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL
                        && candidate.triggerTimestampMs() > 3_000_000)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertTrue(Math.abs(goal.triggerTimestampMs() - actualGoalTimeMs) <= 2_000);
        assertTrue(goal.startTimeMs() <= actualGoalTimeMs);
        assertTrue(goal.endTimeMs() >= actualGoalTimeMs);
        assertTrue(goal.endTimeMs() - goal.startTimeMs() <= quality.maximumCandidateDurationMs());
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS));
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.HIGH_EXCITEMENT));
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION));
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.eventType() == FootballEventType.GOAL), goal.signals().toString());
        assertFalse(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_GOAL));
    }

    @Test
    void recordsScoreChangesButScoreMentionsAloneDoNotCreateGoals() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, "Con 0-1 en el marcador."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 40_000, 42_000, "Ganan a 0-2 al descanso."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 50_000, 52_000, "El resultado final fue 0-2.")
        });
        List<CandidateSignalObservation> context = new CandidateContextSignalDetector(quality).detect(transcript);
        List<CandidateSignal> scoreSignals = context.stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .toList();

        assertEquals(1, scoreSignals.size());
        assertTrue(new CandidateGoalDiscovery(quality).discover(context).isEmpty());
        assertTrue(new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, context, 60_000, NOW).stream()
                .noneMatch(candidate -> candidate.eventType() == FootballEventType.GOAL));
    }

    @Test
    void recognizesLiveGoalAnnouncementPhrasesWithoutTreatingThemAsUnconditionalAcceptance() {
        for (String phrase : List.of("And it's gone in!", "It has gone in!",
                "He's found the net!", "They've found the net!", "Into the back of the net!",
                "Makes it five!", "Makes it 5!")) {
            Transcript transcript = transcript("en", new TranscriptSegment[] {
                    TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, phrase)
            });
            List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings()).detect(transcript);
            CandidateSignal goalSignal = observations.stream().flatMap(observation -> observation.signals().stream())
                    .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                    .findFirst().orElseThrow(() -> new AssertionError("No GOAL signal for: " + phrase));
            assertTrue(goalSignal.confidence() >= 0.80, phrase);
            CandidateEvent candidate = new CandidateEventAssembler(settings()).assemble(
                    transcript.getMediaAssetId(), transcript, observations, 30_000, NOW).stream()
                    .filter(event -> event.eventType() == FootballEventType.GOAL)
                    .findFirst().orElseThrow();
            assertEquals(CandidateEventStatus.DETECTED, candidate.status(), phrase);
        }
    }

    @Test
    void suppressesNegatedRetrospectiveCorrectedAndReplayGoalLanguage() {
        Map<String, String> examples = Map.of(
                "It wasn't scored in that match.", "NEGATED_GOAL_REFERENCE",
                "They had scored earlier.", "HISTORICAL_GOAL_REFERENCE",
                "As we mentioned earlier, he scored.", "HISTORICAL_GOAL_REFERENCE",
                "The fourth goal attribution has been corrected.", "CORRECTED_GOAL_REFERENCE",
                "In the replay, he scores the goal.", "REPLAY_COMMENTARY_REFERENCE");
        for (Map.Entry<String, String> example : examples.entrySet()) {
            Transcript transcript = transcript("en", new TranscriptSegment[] {
                    TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, example.getKey())
            });
            List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings()).detect(transcript);
            CandidateEvent candidate = new CandidateEventAssembler(settings()).assemble(
                    transcript.getMediaAssetId(), transcript, observations, 30_000, NOW).stream()
                    .filter(event -> event.eventType() == FootballEventType.GOAL)
                    .findFirst().orElseThrow(() -> new AssertionError("No diagnostic candidate: "
                            + example.getKey()));

            assertEquals(CandidateEventStatus.REJECTED, candidate.status(), example.getKey());
            assertTrue(candidate.rejectionReasons().contains(example.getValue()), example.getKey());
            assertTrue(candidate.score() < 0.52, example.getKey());
            assertEquals(candidate.score(), candidate.scoreContributions().stream()
                    .mapToDouble(com.clipai.domain.candidate.CandidateScoreComponent::contribution).sum(), 1e-9);
            assertTrue(candidate.scoreContributions().stream().anyMatch(component ->
                    component.type()
                            == com.clipai.domain.candidate.CandidateScoreComponentType.CONTEXTUAL_NEGATIVE_EVIDENCE
                            && component.contribution() < 0), example.getKey());
        }
    }

    @Test
    void keepsLiveFourthGoalAndDoesNotCreateGoalForUnrelatedTextOrPlayerName() {
        Transcript live = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "Portugal have now scored their fourth goal!")
        });
        CandidateEvent liveGoal = new CandidateEventAssembler(settings()).assemble(
                live.getMediaAssetId(), live, new TranscriptSignalDetector(settings()).detect(live),
                30_000, NOW).stream()
                .filter(event -> event.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();
        assertEquals(CandidateEventStatus.DETECTED, liveGoal.status());

        for (String text : List.of("Raphael!", "The goalkeeper is wearing a new shirt.")) {
            Transcript unrelated = transcript("en", new TranscriptSegment[] {
                    TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, text)
            });
            List<CandidateSignalObservation> observations =
                    new TranscriptSignalDetector(settings()).detect(unrelated);
            assertTrue(new CandidateEventAssembler(settings()).assemble(unrelated.getMediaAssetId(),
                    unrelated, observations, 30_000, NOW).stream()
                    .noneMatch(event -> event.eventType() == FootballEventType.GOAL), text);
        }
    }

    @Test
    void recognizesOwnGoalAsTypedEvidenceButRejectsRetrospectiveOwnGoalMention() {
        Transcript live = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, "An own goal!")
        });
        List<CandidateSignalObservation> liveObservations = new TranscriptSignalDetector(settings()).detect(live);
        assertTrue(liveObservations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.OWN_GOAL));
        CandidateEvent liveGoal = new CandidateEventAssembler(settings()).assemble(
                live.getMediaAssetId(), live, liveObservations, 30_000, NOW).stream()
                .filter(event -> event.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();
        assertEquals(CandidateEventStatus.DETECTED, liveGoal.status());

        Transcript historical = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "Earlier in the match, it was an own goal.")
        });
        List<CandidateSignalObservation> historicalObservations =
                new TranscriptSignalDetector(settings()).detect(historical);
        CandidateEvent rejected = new CandidateEventAssembler(settings()).assemble(
                historical.getMediaAssetId(), historical, historicalObservations, 30_000, NOW).stream()
                .filter(event -> event.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();
        assertEquals(CandidateEventStatus.REJECTED, rejected.status());
        assertTrue(rejected.rejectionReasons().contains("HISTORICAL_GOAL_REFERENCE"));
    }

    @Test
    void acceptsOnlyPlausibleSingleScoreTransitionsAndRecoversAfterSkippedScoreObservation() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        assertEquals(1, scoreTransitions(quality, "The score is 1-1.", "Now it's 2-1.").size());
        assertEquals(1, scoreTransitions(quality, "The score is 3-0.", "Now it's 4-0.").size());
        assertEquals(1, scoreTransitions(quality, "The score is 1-1.",
                "The score is 3-0.", "Now it's 4-0.").size());
        assertTrue(scoreTransitions(quality, "The score is 1-1.",
                "Earlier, the score was 2-1.", "The final score was 3-1.").isEmpty());
        assertTrue(scoreTransitions(quality, "The score is 1-1.", "Now it's 4-0.").isEmpty());
        assertTrue(scoreTransitions(quality, "The score is 2-0.",
                "Correction: the score is 1-0.", "The score remains 2-0.").isEmpty());
        assertTrue(scoreTransitions(quality, "The score is 1-0.", "The score is 21-0.").isEmpty());
    }

    @Test
    void discoversGoalFromModerateIndependentAudioFeaturesAndTypedActionOnly() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<CandidateSignalObservation> corroborated = List.of(
                signal(CandidateSignalType.VOICE_EXCITEMENT, null, 0.64, 20_000),
                signal(CandidateSignalType.PITCH_VARIANCE, null, 0.69, 20_000),
                signal(CandidateSignalType.SPEECH_RATE_SPIKE, null, 0.84, 20_000),
                signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 0.82, 21_000));
        assertFalse(new CandidateGoalDiscovery(quality).discover(corroborated).isEmpty());

        List<CandidateSignalObservation> crowdOnly = List.of(
                signal(CandidateSignalType.AUDIO_SPIKE, null, 0.99, 20_000),
                signal(CandidateSignalType.CROWD_REACTION_PROXY, null, 0.95, 20_000),
                signal(CandidateSignalType.SPEECH_RATE_SPIKE, null, 0.84, 20_000));
        assertTrue(new CandidateGoalDiscovery(quality).discover(crowdOnly).isEmpty());

        assertTrue(new CandidateGoalDiscovery(quality).discover(List.of(
                signal(CandidateSignalType.VOICE_EXCITEMENT, null, 0.64, 20_000),
                signal(CandidateSignalType.PITCH_VARIANCE, null, 0.69, 20_000))).isEmpty());
        assertTrue(new CandidateGoalDiscovery(quality).discover(List.of(
                signal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT, 0.82, 20_000))).isEmpty());
        assertFalse(new CandidateGoalDiscovery(quality).discover(List.of(
                signal(CandidateSignalType.VOICE_EXCITEMENT, null, 0.64, 20_000),
                signal(CandidateSignalType.PITCH_VARIANCE, null, 0.69, 20_000),
                signal(CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.82, 22_000))).isEmpty());
    }

    @Test
    void doesNotTreatScoreDescriptionInsideReplayAsANewScoreTransition() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000, "The score is now 1-0."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 70_000, 72_000,
                        "In the replay, the goal makes it 1-1.")
        });

        List<CandidateSignal> scoreSignals = new CandidateContextSignalDetector(quality).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .toList();

        assertTrue(scoreSignals.isEmpty());
    }

    @Test
    void recognizesFrenchReplayIntrosAndPostGoalMidfieldPlay() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "C'est le but, les joueurs célèbrent."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 20_000, 22_000,
                        "On va revoir tout de suite l'égalisation rennaise."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 30_000, 32_000,
                        "Le ballon repart au milieu de terrain.")
        });

        List<CandidateSignal> signals = new CandidateContextSignalDetector(quality).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream()).toList();

        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.REPLAY_CONTEXT));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.RESTART_CONTEXT));
    }

    @Test
    void capsGoalBoundaryAtTheFirstRestartAfterImmediateReaction() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "He scores the goal!"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 20_000, 22_000,
                        "The players celebrate as the crowd roars."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 32_000, 34_000,
                        "Kickoff, and the match is back underway.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));
        observations.add(new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                CandidateSignalType.AUDIO_SPIKE, null, 0.90, 10_000, "Goal reaction audio"))));

        CandidateEvent goal = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(32_000, goal.endTimeMs());
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_AFTERGLOW
                        && signal.evidence().contains("POST_RESTART")));
        assertTrue(goal.endTimeMs() - goal.startTimeMs() <= quality.maximumCandidateDurationMs());
    }

    @Test
    void associatesInjuryFollowedByReplayOnlyWithAnEstablishedSameActionGoal() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "Raphinha scores for Barcelona after a cross into the box."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 25_000, 27_000,
                        "The injured player receives medical attention before the replay shows "
                                + "Raphinha scored for Barcelona after a cross into the box, goooool!")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));
        observations.add(new CandidateSignalObservation(26_000, List.of(new CandidateSignal(
                CandidateSignalType.AUDIO_SPIKE, null, 0.96, 26_000, "Very strong replay audio"))));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL).toList();

        assertEquals(2, candidates.size());
        CandidateEvent live = candidates.stream()
                .filter(candidate -> candidate.status() == CandidateEventStatus.DETECTED)
                .findFirst().orElseThrow();
        CandidateEvent replay = candidates.stream()
                .filter(candidate -> candidate.status() == CandidateEventStatus.REJECTED)
                .findFirst().orElseThrow();
        assertTrue(replay.triggerTimestampMs() > live.triggerTimestampMs());
        assertEquals(CandidateEventStatus.REJECTED, replay.status());
        assertTrue(replay.rejectionReasons().contains("REPLAY_OF_EXISTING_GOAL"));
        assertTrue(replay.rejectionReasons().contains("INJURY_THEN_REPLAY"));
        assertTrue(replay.replayProbability() >= quality.replayProbabilityThreshold());
    }

    @Test
    void reconstructsRecentLiveAttackFromExplicitReplayAndScoringEvidenceWithoutEmittingReplayAsGoal() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 100_000, 102_000,
                        "The attack builds; the final pass goes to the scorer."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 120_000, 122_000,
                        "The passer finds the scorer."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 141_000, 142_000,
                        "We are going to review that action right now."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 143_000, 145_000,
                        "The equalizing goal for Brest came from a low pass to the scorer.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(100_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_EVENT,
                                FootballEventType.ATTACK, 0.72, 100_000, "Football phrase match: attack"),
                        new CandidateSignal(CandidateSignalType.SPEECH_RATE_SPIKE,
                                FootballEventType.COMMENTATOR_REACTION, 0.60, 100_000,
                                "Speech rate increased during the live attack"))),
                new CandidateSignalObservation(141_000, List.of(new CandidateSignal(
                        CandidateSignalType.REPLAY_CONTEXT, null, 0.84, 141_000,
                        "Context phrase or structure match: explicit replay introduction"))),
                new CandidateSignalObservation(143_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                                FootballEventType.GOAL, 0.88, 143_000,
                                "Explicit scoring phrase in replay commentary"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                                FootballEventType.GOAL, 0.88, 143_000,
                                "Football phrase match: equalizing goal"))));

        List<CandidateEvent> goals = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 200_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL).toList();

        assertEquals(1, goals.size());
        CandidateEvent reconstructed = goals.getFirst();
        assertEquals(100_000, reconstructed.triggerTimestampMs());
        assertEquals(CandidateEventStatus.DETECTED, reconstructed.status());
        assertTrue(reconstructed.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.evidence().startsWith("RETROSPECTIVE_GOAL_CONFIRMATION:")));
        assertTrue(reconstructed.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && signal.evidence().startsWith("REPLAY_EVIDENCE_FOR_LIVE_EVENT;")));
        assertFalse(reconstructed.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.REPLAY_CONTEXT));
        assertTrue(reconstructed.startTimeMs() <= reconstructed.triggerTimestampMs());
        assertTrue(reconstructed.endTimeMs() >= reconstructed.triggerTimestampMs());
    }

    @Test
    void doesNotReconstructRecentAttackAcrossAnInterveningScoreTransition() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 100_000, 102_000,
                        "The attack builds; the final pass goes to the scorer."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 120_000, 122_000,
                        "The passer finds the scorer."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 141_000, 142_000,
                        "We are going to review that action right now."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 143_000, 145_000,
                        "The equalizing goal for Brest came from a low pass to the scorer.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(100_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_EVENT,
                                FootballEventType.ATTACK, 0.72, 100_000, "Football phrase match: attack"),
                        new CandidateSignal(CandidateSignalType.SPEECH_RATE_SPIKE,
                                FootballEventType.COMMENTATOR_REACTION, 0.60, 100_000,
                                "Speech rate increased during the live attack"))),
                new CandidateSignalObservation(125_000, List.of(new CandidateSignal(
                        CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.90, 125_000,
                        "A new score state occurred after the attack"))),
                new CandidateSignalObservation(141_000, List.of(new CandidateSignal(
                        CandidateSignalType.REPLAY_CONTEXT, null, 0.84, 141_000,
                        "Context phrase or structure match: explicit replay introduction"))),
                new CandidateSignalObservation(143_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                                FootballEventType.GOAL, 0.88, 143_000,
                                "Explicit scoring phrase in replay commentary"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                                FootballEventType.GOAL, 0.88, 143_000,
                                "Football phrase match: equalizing goal"))));

        List<CandidateEvent> goals = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 200_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL).toList();

        assertEquals(1, goals.size());
        assertEquals(143_000, goals.getFirst().triggerTimestampMs());
        assertFalse(goals.getFirst().signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.evidence().startsWith("RETROSPECTIVE_GOAL_CONFIRMATION:")));
    }

    @Test
    void excitementAndAttackWithoutScoreTransitionRemainUnclassified() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.HIGH_EXCITEMENT, null, 0.91, 10_000,
                        "Sustained voiced excitement with pitch and energy evidence"))),
                new CandidateSignalObservation(16_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.ATTACK,
                        0.72, 16_000, "Football phrase match: attack"))));

        assertTrue(new CandidateGoalDiscovery(quality).discover(observations).isEmpty());
    }

    @Test
    void rejectsAudioGoalHypothesesWithAReplayCueOrExplicitNonGoalOutcome() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        for (FootballEventType outcome : List.of(FootballEventType.SAVE, FootballEventType.NEAR_MISS)) {
            List<CandidateSignalObservation> observations = List.of(
                    new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                            CandidateSignalType.HIGH_EXCITEMENT, null, 0.91, 10_000, "Strong live voice excitement"))),
                    new CandidateSignalObservation(12_000, List.of(new CandidateSignal(
                            CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.ATTACK,
                            0.72, 12_000, "Football action cue"))),
                    new CandidateSignalObservation(14_000, List.of(new CandidateSignal(
                            CandidateSignalType.TRANSCRIPT_EVENT, outcome, 0.80, 14_000,
                            "Explicit non-goal outcome"))),
                    new CandidateSignalObservation(18_000, List.of(new CandidateSignal(
                            CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.82, 18_000,
                            "Later score change"))));
            assertTrue(new CandidateGoalDiscovery(quality).discover(observations).isEmpty(), outcome.name());
        }

        List<CandidateSignalObservation> replay = List.of(
                new CandidateSignalObservation(9_000, List.of(new CandidateSignal(
                        CandidateSignalType.REPLAY_CONTEXT, null, 0.84, 9_000, "Replay introduction"))),
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.HIGH_EXCITEMENT, null, 0.91, 10_000, "Replay audio"))),
                new CandidateSignalObservation(12_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.ATTACK,
                        0.72, 12_000, "Replay action cue"))),
                new CandidateSignalObservation(18_000, List.of(new CandidateSignal(
                        CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.82, 18_000, "Score mention"))));
        assertEquals(1, new CandidateGoalDiscovery(quality).discover(replay).size());
    }

    @Test
    void discoversShotWithVoiceAndSpeechRateWithoutRequiringAScoreTransition() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(30_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        0.78, 30_000, "French commentary identifies a shot"))),
                new CandidateSignalObservation(31_000, List.of(new CandidateSignal(
                        CandidateSignalType.VOICE_EXCITEMENT, null, 0.79,
                        31_000, "Independent crowd and commentator reaction"))),
                new CandidateSignalObservation(32_000, List.of(new CandidateSignal(
                        CandidateSignalType.SPEECH_RATE_SPIKE, FootballEventType.COMMENTATOR_REACTION,
                        0.68, 32_000, "Commentary rate increases after shot"))),
                new CandidateSignalObservation(29_000, List.of(new CandidateSignal(
                        CandidateSignalType.REPLAY_CONTEXT, null, 0.84,
                        29_000, "Replay introduction"))));

        List<CandidateSignalObservation> candidates = new CandidateGoalDiscovery(quality).discover(observations);

        assertEquals(1, candidates.size());
        assertTrue(candidates.getFirst().signals().stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL));
        assertFalse(candidates.getFirst().signals().stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION));
    }

    @Test
    void retainsUnconfirmedAudioGoalHypothesisForReviewButDoesNotAcceptItAsCanonicalGoal() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000, "He takes a shot.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(30_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                                FootballEventType.GOAL, 0.84, 30_000,
                                "Inferred goal hypothesis from a shot, independent audio reaction, and speech-rate increase"),
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.84, 30_000,
                                "Audio-led GOAL hypothesis from a shot and excitement"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                                FootballEventType.SHOT, 0.82, 30_000, "Shot detected"),
                        new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                                null, 0.82, 30_000, "Independent live reaction"),
                        new CandidateSignal(CandidateSignalType.SPEECH_RATE_SPIKE,
                                null, 0.70, 30_000, "Speech rate increased"))));

        CandidateEvent hypothesis = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.REJECTED, hypothesis.status());
        assertTrue(hypothesis.rejectionReasons().contains("GOAL_HYPOTHESIS_UNCONFIRMED"));
        assertTrue(hypothesis.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.AUDIO_GOAL_HYPOTHESIS));
    }

    @Test
    void acceptsShotAndLiveReactionWhenCorroboratedByNearbyPenaltyContext() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 28_000, 29_000,
                        "Le joueur frappe le penalty."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000,
                        "Le tir part avec une forte réaction du public.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(30_000, List.of(
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.84, 30_000,
                                "Audio-led hypothesis near the penalty attempt"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                                FootballEventType.SHOT, 0.82, 30_000, "Penalty shot"),
                        new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                                null, 0.82, 30_000, "Independent live reaction"))));

        CandidateEvent goal = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && signal.evidence().startsWith("PENALTY_ATTEMPT_WITH_INDEPENDENT_REACTION")));
    }

    @Test
    void rejectsExcitedBlockedShotWhenTheAttackContinues() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000,
                        "The shot is blocked."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 34_000, 35_000,
                        "The attack continues.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(30_000, List.of(
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.84, 30_000, "Excited shot hypothesis"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                                FootballEventType.SHOT, 0.82, 30_000, "Shot cue"),
                        new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                                null, 0.82, 30_000, "Independent reaction"))),
                new CandidateSignalObservation(34_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.ATTACK,
                        0.72, 34_000, "Football phrase match: attack continues"))));

        CandidateEvent hypothesis = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.REJECTED, hypothesis.status());
        assertTrue(hypothesis.rejectionReasons().contains("NON_SCORING_SHOT_CONTINUED_PLAY"));
    }

    @Test
    void rejectsUnconfirmedShotWithinCornerSequenceWithExplainableReason() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 24_000, 25_000,
                        "They are setting up another corner."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000, "The shot comes in.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(30_000, List.of(
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.84, 30_000, "Audio shot hypothesis"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                                FootballEventType.SHOT, 0.82, 30_000, "Shot from a corner"),
                        new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                                null, 0.82, 30_000, "Independent reaction"))));

        CandidateEvent hypothesis = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.REJECTED, hypothesis.status());
        assertTrue(hypothesis.rejectionReasons().contains("SET_PIECE_SHOT_WITHOUT_SCORING_CONFIRMATION"));
        assertTrue(hypothesis.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.UNRELATED_ACTION_CONTEXT
                        && signal.evidence().startsWith("SET_PIECE_SHOT_WITHOUT_SCORING_CONFIRMATION;")));
    }

    @Test
    void flagsRememberedGoalDescriptionAsRetrospectiveNotLiveScoringEvidence() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 28_000, 30_000,
                        "Un but de la tête, si je me souviens bien."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000,
                        "Il tente sa chance.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new CandidateContextSignalDetector(quality).detect(transcript));
        observations.add(new CandidateSignalObservation(30_000, List.of(
                new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                        FootballEventType.GOAL, 0.84, 30_000, "Shot and excitement hypothesis"),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                        FootballEventType.SHOT, 0.82, 30_000, "Shot cue"),
                new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                        null, 0.82, 30_000, "Independent reaction"))));

        CandidateEvent hypothesis = new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.REJECTED, hypothesis.status());
        assertTrue(hypothesis.rejectionReasons().contains("RETROSPECTIVE_GOAL_REFERENCE"));
        assertTrue(hypothesis.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                        && signal.evidence().startsWith("RETROSPECTIVE_GOAL_REFERENCE;")));
    }

    @Test
    void recognizesCurrentScoringCommentaryAfterThePhraseJustAfterTheGoal() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "Juste après le but qu'on vient de marquer, on reprend au milieu.")
        });

        List<CandidateSignal> goalSignals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL)
                .toList();

        assertFalse(goalSignals.isEmpty());
    }

    @Test
    void sustainedVoiceExcitementIsAnEventAgnosticAudioSignal() {
        CandidateDetectionSettings settings = settings();

        List<CandidateSignal> signals = new AudioExcitementDetector(settings,
                CandidateDetectionQualitySettings.defaults()).detect(sustainedGoalCallAudio()).stream()
                .flatMap(observation -> observation.signals().stream())
                .toList();

        CandidateSignal excitement = signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.HIGH_EXCITEMENT)
                .findFirst().orElseThrow();
        assertTrue(excitement.eventType() == null);
        assertTrue(excitement.confidence() >= 0.72);
        assertTrue(excitement.evidence().contains("PITCH_RISE"));
        assertTrue(excitement.evidence().contains("PITCH_VARIANCE"));
    }

    @Test
    void canonicalizesMultipleAudioWindowsForTheSameGoalIntoOneEvent() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "He drives into the area."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 20_000, 21_000, "The score is now 1-0.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(15_000, List.of(new CandidateSignal(
                        CandidateSignalType.HIGH_EXCITEMENT, null, 0.90, 15_000, "Excitement window one"))),
                new CandidateSignalObservation(17_000, List.of(new CandidateSignal(
                        CandidateSignalType.HIGH_EXCITEMENT, null, 0.92, 17_000, "Excitement window two"))),
                new CandidateSignalObservation(16_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_EVENT, FootballEventType.ATTACK,
                        0.75, 16_000, "Attack context"))),
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.82, 20_000, "Score transition"))));
        List<CandidateSignalObservation> discovered = new CandidateGoalDiscovery(quality).discover(observations);
        List<CandidateEvent> raw = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, combine(observations, discovered), 60_000, NOW);

        List<CandidateEvent> canonical = new CandidateEventClusterer(
                CandidateEventClusteringSettings.defaults(), quality).cluster(raw).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .toList();

        assertEquals(1, canonical.size());
        assertEquals(2, canonical.getFirst().sourceCandidateIds().size());
        assertTrue(canonical.getFirst().signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_MERGE));
    }

    @Test
    void laterRetrospectiveCommentaryDoesNotPenalizeAnEarlierLiveGoal() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "Scores the goal!"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 24_000, 25_000,
                        "As we saw earlier in the match.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 20_000, "Scores the goal"))),
                new CandidateSignalObservation(20_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_SPIKE, null, 0.86, 20_000, "Live audio"))),
                new CandidateSignalObservation(24_000, List.of(new CandidateSignal(
                        CandidateSignalType.RETROSPECTIVE_CONTEXT, null, 0.90, 24_000,
                        "Context phrase or structure match: earlier in the match"))));

        CandidateEvent goal = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertEquals(0, goal.replayProbability());
    }

    @Test
    void reconstructsGoalWindowFromEarlierShotWhenScoringCommentaryArrivesLate() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "Crosses into the box."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 20_000, 21_000, "He strikes it cleanly."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 40_000, 41_000, "Raphinha scores for Barcelona.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));

        CandidateEvent goal = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 90_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertEquals(40_000, goal.triggerTimestampMs());
        assertTrue(goal.startTimeMs() <= 15_000);
        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.timestampMs() == 20_000));
        assertTrue(goal.startTimeMs() <= goal.triggerTimestampMs());
        assertTrue(goal.endTimeMs() >= goal.triggerTimestampMs());
        assertTrue(goal.startTimeMs() <= 20_000 && goal.endTimeMs() >= 20_000);
        assertTrue(goal.endTimeMs() - goal.startTimeMs() <= quality.maximumCandidateDurationMs());
    }

    @Test
    void scoreTransitionSupportedAudioGoalKeepsItsTriggerInWindow() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "The score is now one nil.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(
                        new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT, null,
                                0.76, 10_000, "Independent voice excitement"),
                        new CandidateSignal(CandidateSignalType.PITCH_RISE, null,
                                0.72, 10_000, "Independent pitch rise"))),
                new CandidateSignalObservation(20_000, List.of(
                        new CandidateSignal(CandidateSignalType.SCORE_STATE_TRANSITION, null,
                                0.84, 20_000, "Transcript score changed from 0-0 to 1-0"))));
        List<CandidateSignalObservation> discovered =
                new CandidateGoalDiscovery(quality).discover(observations);

        CandidateEvent goal = new CandidateEventAssembler(settings(), quality).assemble(
                transcript.getMediaAssetId(), transcript, combine(observations, discovered),
                60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertTrue(goal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION));
        assertTrue(goal.startTimeMs() <= goal.triggerTimestampMs());
        assertTrue(goal.endTimeMs() >= goal.triggerTimestampMs());
    }

    @Test
    void audioGoalWindowContainsTriggerWhenReconstructedActionIsLater() {
        long triggerTimestampMs = 867_500;
        long actionTimestampMs = 877_840;
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, actionTimestampMs,
                        actionTimestampMs + 1_000, "El remate va al portero.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(triggerTimestampMs, List.of(
                        new CandidateSignal(CandidateSignalType.AUDIO_GOAL_HYPOTHESIS,
                                FootballEventType.GOAL, 0.86, triggerTimestampMs,
                                "Audio-backed GOAL hypothesis from an audio reaction and typed action"))),
                new CandidateSignalObservation(actionTimestampMs, List.of(
                        new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION,
                                FootballEventType.GOAL, 0.86, actionTimestampMs,
                                "Goal action is anchored to typed football-action evidence at "
                                        + actionTimestampMs + " ms"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT,
                                FootballEventType.SHOT, 0.88, actionTimestampMs,
                                "Football phrase match: remate"))));

        CandidateEvent goal = new CandidateEventAssembler(settings(),
                CandidateDetectionQualitySettings.defaults()).assemble(
                transcript.getMediaAssetId(), transcript, observations, 940_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();

        assertEquals(triggerTimestampMs, goal.triggerTimestampMs());
        assertEquals(triggerTimestampMs, goal.startTimeMs());
        assertFalse(goal.startTimeMs() == 872_840);
        assertTrue(goal.startTimeMs() <= triggerTimestampMs);
        assertTrue(goal.endTimeMs() >= triggerTimestampMs);
        assertTrue(goal.startTimeMs() <= actionTimestampMs);
        assertTrue(goal.endTimeMs() >= actionTimestampMs);
    }

    @Test
    void doesNotReuseAnotherGoalHypothesisActionWhenBuildingCandidateBoundaries() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "Scores the goal!"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 100_000, 101_000, "Drives into the area.")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 10_000, "Scores the goal"))),
                new CandidateSignalObservation(100_000, List.of(new CandidateSignal(
                        CandidateSignalType.AUDIO_GOAL_HYPOTHESIS, FootballEventType.GOAL,
                        0.88, 100_000, "Audio-led goal hypothesis"))),
                new CandidateSignalObservation(100_000, List.of(new CandidateSignal(
                        CandidateSignalType.EVENT_RECONSTRUCTION, FootballEventType.GOAL,
                        0.88, 100_000, "Event action is anchored to the sustained audio-excitement onset"))),
                new CandidateSignalObservation(100_000, List.of(new CandidateSignal(
                        CandidateSignalType.HIGH_EXCITEMENT, null, 0.91, 100_000, "Sustained voice excitement"))),
                new CandidateSignalObservation(110_000, List.of(new CandidateSignal(
                        CandidateSignalType.SCORE_STATE_TRANSITION, null, 0.82, 110_000, "Score transition"))));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 140_000, NOW);
        CandidateEvent earlierGoal = candidates.stream()
                .filter(candidate -> candidate.triggerTimestampMs() == 10_000)
                .findFirst().orElseThrow();
        CandidateEvent audioGoal = candidates.stream()
                .filter(candidate -> candidate.triggerTimestampMs() == 100_000)
                .findFirst().orElseThrow();

        assertTrue(earlierGoal.startTimeMs() <= earlierGoal.triggerTimestampMs());
        assertTrue(earlierGoal.endTimeMs() >= earlierGoal.triggerTimestampMs());
        assertFalse(earlierGoal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.evidence().startsWith(
                        "Event action is anchored to the sustained audio-excitement onset")));
        assertTrue(audioGoal.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.timestampMs() == audioGoal.triggerTimestampMs()));
    }

    @Test
    void associatesPenaltyWithNearbyFoulOnlyWhenTranscriptContextOverlaps() {
        CandidateDetectionSettings settings = settings();
        Transcript supported = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000,
                        "Foul on Garcia after a late tackle."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 15_000, 16_000,
                        "Penalty awarded after the tackle on Garcia.")
        });
        Transcript unrelated = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000,
                        "Foul on Garcia after a late tackle."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 15_000, 16_000,
                        "Penalty awarded after handball by Lopez.")
        });

        CandidateEvent supportedPenalty = penaltyCandidate(settings, supported);
        CandidateEvent unrelatedPenalty = penaltyCandidate(settings, unrelated);

        assertTrue(supportedPenalty.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION
                        && signal.evidence().contains("preceding FOUL cue")));
        assertTrue(supportedPenalty.startTimeMs() <= 5_000);
        assertFalse(unrelatedPenalty.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_ASSOCIATION));
    }

    @Test
    void anchorsCardWindowToAContextuallyLinkedFoul() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000,
                        "Faute sur Garcia après un tacle en retard."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 15_000, 16_000,
                        "Carton jaune pour Garcia.")
        });
        List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings).detect(transcript);

        CandidateEvent card = new CandidateEventAssembler(settings).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.YELLOW_CARD)
                .findFirst().orElseThrow();

        assertTrue(card.startTimeMs() <= 5_000);
        assertTrue(card.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_RECONSTRUCTION
                        && signal.evidence().contains("preceding contextual FOUL cue")));
    }

    @Test
    void usesCompactBoundariesForCardCandidates() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 40_000, 41_000, "Carton jaune.")
        });
        CandidateEvent card = new CandidateEventAssembler(settings).assemble(
                transcript.getMediaAssetId(), transcript,
                new TranscriptSignalDetector(settings).detect(transcript), 80_000, NOW).getFirst();

        assertEquals(CandidateEventStatus.DETECTED, card.status());
        assertEquals(5_000, card.triggerTimestampMs() - card.startTimeMs());
        assertEquals(8_000, card.endTimeMs() - card.triggerTimestampMs());
        assertTrue(card.endTimeMs() - card.startTimeMs() < 20_000);
    }

    @Test
    void doesNotTreatGeneralFrenchGoalReferencesAsGoalsButFindsExplicitEvents() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "On peut revoir le but."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000, "Il vient de marquer !"),
                TranscriptSegment.create(UUID.randomUUID(), 2, 5_000, 6_000, "Carton jaune, il est averti."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 7_000, 8_000, "Frappe au but.")
        });

        var signals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD)
                .toList();

        assertTrue(signals.stream().anyMatch(signal -> signal.eventType() == FootballEventType.GOAL
                && signal.timestampMs() == 3_000));
        assertTrue(signals.stream().noneMatch(signal -> signal.eventType() == FootballEventType.GOAL
                && signal.timestampMs() == 1_000));
        assertTrue(signals.stream().anyMatch(signal -> signal.eventType() == FootballEventType.YELLOW_CARD));
        assertTrue(signals.stream().anyMatch(signal -> signal.eventType() == FootballEventType.SHOT));
    }

    @Test
    void repairsMojibakeAndRecognizesFrenchScoringAndEqualizerCalls() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000,
                        "C'est plutôt inscrit par Eric Dina et Bimbe."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000,
                        "Le joueur a marquÃ© !"),
                TranscriptSegment.create(UUID.randomUUID(), 2, 5_000, 6_000,
                        "L'Ã©galisation rennaise."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 7_000, 8_000,
                        "Juste après le but qu'on vient de marquer.")
        });

        var goals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.GOAL)
                .toList();

        assertEquals(List.of(1_000L, 3_000L, 5_000L, 7_000L),
                goals.stream().map(signal -> signal.timestampMs()).toList());
        assertEquals("le joueur a marque",
                TranscriptTextNormalizer.normalize("Le joueur a marquÃ©"));
    }

    @Test
    void excludesReplayAndHistoricalReferencesAndSuppressesRepeatedGoalReplayCalls() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "On revoit le but."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000, "Il avait marqué au match allé."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 5_000, 6_000, "Face au but."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 7_000, 8_000, "Parce que ce deuxième but."),
                TranscriptSegment.create(UUID.randomUUID(), 4, 10_000, 11_000, "Il vient de marquer le but rennais."),
                TranscriptSegment.create(UUID.randomUUID(), 5, 49_000, 50_000, "Il vient de marquer le but rennais.")
        });

        var goals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.GOAL)
                .toList();

        assertEquals(List.of(10_000L), goals.stream()
                .map(CandidateSignal::timestampMs).toList());
    }

    @Test
    void recognizesFrenchComebackAndPenaltyScoreChangesButLeavesMissesAsPenalties() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000, "Penalty pour Brest."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000, "C'est parti, le joueur frappe."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 20_000, 21_000,
                        "Cette action permet à Rennes de mener."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 60_000, 61_000,
                        "Une occasion de remettre Brest dans ce match."),
                TranscriptSegment.create(UUID.randomUUID(), 4, 70_000, 71_000,
                        "Le gardien arrête le penalty."),
                TranscriptSegment.create(UUID.randomUUID(), 5, 100_000, 101_000,
                        "Le joueur tire le penalty, le gardien arrête le penalty."),
                TranscriptSegment.create(UUID.randomUUID(), 6, 200_000, 201_000,
                        "Elle est pour remettre à Brest dans ce match.")
        });

        var goals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.GOAL)
                .toList();

        assertEquals(List.of(1_000L, 200_000L),
                goals.stream().map(signal -> signal.timestampMs()).sorted().toList());
        assertTrue(goals.stream().anyMatch(signal -> signal.evidence().contains("Penalty attempt")));
    }

    @Test
    void avoidsGenericSpanishGoalReferencesAndMatchesExplicitScoringCalls() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("es", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 1_000, 2_000,
                        "Ferrán marcó el histórico gol de la final."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 3_000, 4_000,
                        "En el Mundial, un gol anulado."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 5_000, 6_000,
                        "Salva un gol en la línea."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 7_000, 8_000,
                        "Anotó el primer tanto para el Barça."),
                TranscriptSegment.create(UUID.randomUUID(), 4, 9_000, 10_000,
                        "Transforma el primer tanto del Barcelona."),
                TranscriptSegment.create(UUID.randomUUID(), 5, 11_000, 12_000,
                        "Abre el marcador para el Barcelona.")
        });

        var goals = new TranscriptSignalDetector(settings).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.eventType() == FootballEventType.GOAL)
                .toList();

        assertEquals(List.of(7_000L, 9_000L, 11_000L),
                goals.stream().map(signal -> signal.timestampMs()).toList());
    }

    @Test
    void findsRelativeAudioSpikesAndSustainedIntensity() {
        CandidateDetectionSettings settings = settings();
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            double dbfs = index == 4 || index == 5 ? -10 : -30;
            windows.add(new AudioEnergyWindow(500L + index * 1_000L, dbfs));
        }

        var observations = new AudioExcitementDetector(settings)
                .detect(new AudioEnergyAnalysis(10_000, windows));
        var signals = observations.stream().flatMap(observation -> observation.signals().stream()).toList();

        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.AUDIO_SPIKE));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.AUDIO_SUSTAINED));
        assertTrue(signals.stream().filter(signal -> signal.type() == CandidateSignalType.AUDIO_SUSTAINED)
                .allMatch(signal -> signal.eventType() == null));
        Transcript transcript = transcript("en", new TranscriptSegment[0]);
        List<CandidateEvent> candidates = new CandidateEventAssembler(settings).assemble(
                transcript.getMediaAssetId(), transcript, observations, 10_000, NOW);
        assertTrue(candidates.isEmpty());
    }

    @Test
    void mergesNearbySignalsIntoOneScoredCandidateWithContext() {
        CandidateDetectionSettings settings = settings();
        UUID mediaAssetId = UUID.randomUUID();
        Transcript transcript = transcript("pt", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 4_500, 5_200, "GOL!!!")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            windows.add(new AudioEnergyWindow(500L + index * 1_000L,
                    index == 4 || index == 5 ? -10 : -30));
        }
        observations.addAll(new AudioExcitementDetector(settings)
                .detect(new AudioEnergyAnalysis(12_000, windows)));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings)
                .assemble(mediaAssetId, transcript, observations, 12_000, NOW);

        assertEquals(1, candidates.size());
        CandidateEvent candidate = candidates.getFirst();
        assertEquals(FootballEventType.GOAL, candidate.eventType());
        assertEquals(CandidateEventStatus.DETECTED, candidate.status());
        assertTrue(candidate.score() >= settings.minimumCandidateScore());
        assertEquals("GOL!!!", candidate.transcriptContext());
        assertEquals(0, candidate.startTimeMs());
        assertTrue(candidate.endTimeMs() <= 12_000);
    }

    @Test
    void recordsWeakGoalLanguageAsRejectedWithExplainableReasonAndThirtySecondFallback() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 40_000, 41_000, "goal")
        });
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings).detect(transcript);

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 90_000, NOW);

        CandidateEvent candidate = candidates.getFirst();
        assertEquals(CandidateEventStatus.REJECTED, candidate.status());
        assertEquals(10_000, candidate.startTimeMs());
        assertTrue(candidate.rejectionReasons().contains("GOAL_WITHOUT_REACTION_CORROBORATION"));
    }

    @Test
    void detectsHighConfidenceScoringPhraseWithoutAudioReactionAtExistingSingleSignalThreshold() {
        CandidateDetectionSettings settings = settings();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 40_000, 41_000, "He scores.")
        });
        List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings).detect(transcript);

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings,
                CandidateDetectionQualitySettings.defaults()).assemble(
                transcript.getMediaAssetId(), transcript, observations, 90_000, NOW);

        CandidateEvent candidate = candidates.getFirst();
        assertEquals(CandidateEventStatus.DETECTED, candidate.status());
        assertTrue(candidate.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.TRANSCRIPT_KEYWORD
                        && signal.evidence().startsWith("Football phrase match: scores")));
    }

    @Test
    void doesNotTurnSeasonStatisticsOrScoreExplanationIntoNewGoals() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 12_000,
                        "He scored his third goal of the season."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 40_000, 42_000,
                        "The final score was 3-2; he scored twice.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        List<CandidateSignalObservation> context = new CandidateContextSignalDetector(quality).detect(transcript);
        observations.addAll(context);

        assertFalse(observations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.TRANSCRIPT_GOAL));
        assertTrue(context.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.RETROSPECTIVE_CONTEXT
                        && signal.confidence() >= quality.replayProbabilityThreshold()));
        assertTrue(new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).isEmpty());
    }

    @Test
    void detectsCurrentGoalDespiteSeasonReferenceWhenLiveReactionIsPresent() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000,
                        "He scores his third goal of the season!")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));

        CandidateEvent candidate = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 70_000, NOW).getFirst();

        assertEquals(FootballEventType.GOAL, candidate.eventType());
        assertEquals(CandidateEventStatus.DETECTED, candidate.status());
        assertTrue(candidate.replayProbability() < quality.replayProbabilityThreshold());
    }

    @Test
    void doesNotPromoteGenericAttackCommentaryWithoutBuildupAndReaction() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000,
                        "Une attaque, offensive, pressing, profondeur, surface et centre.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW);

        assertTrue(candidates.stream().noneMatch(candidate ->
                candidate.eventType() == FootballEventType.ATTACK
                        && candidate.status() == CandidateEventStatus.DETECTED));
    }

    @Test
    void detectsSpecificShotAndCardLanguageAsSeparateEventTypes() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("fr", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "Frappe au but."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 40_000, 41_000,
                        "Carton jaune, il est averti.")
        });
        List<CandidateSignalObservation> observations =
                new TranscriptSignalDetector(settings).detect(transcript);

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 80_000, NOW);

        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.eventType() == FootballEventType.SHOT
                        && candidate.status() == CandidateEventStatus.DETECTED));
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.eventType() == FootballEventType.YELLOW_CARD
                        && candidate.status() == CandidateEventStatus.DETECTED));
    }

    @Test
    void keepsNearbyGoalsSeparateAndBoundsEachWindowAroundItsOwnTrigger() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "Scores the goal!"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 32_000, 33_000, "Finds the net!")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.add(new CandidateSignalObservation(20_000, List.of(
                new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null, 0.90,
                        20_000, "Independent local reaction"))));
        observations.add(new CandidateSignalObservation(32_000, List.of(
                new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null, 0.90,
                        32_000, "Independent local reaction"))));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 80_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL
                        && candidate.status() == CandidateEventStatus.DETECTED)
                .sorted(Comparator.comparingLong(CandidateEvent::triggerTimestampMs))
                .toList();

        assertEquals(List.of(20_000L, 32_000L),
                candidates.stream().map(CandidateEvent::triggerTimestampMs).toList());
        assertEquals(0, candidates.getFirst().startTimeMs());
        assertEquals(2_000, candidates.getLast().startTimeMs());
        assertTrue(candidates.stream().allMatch(candidate ->
                candidate.endTimeMs() - candidate.startTimeMs() <= quality.maximumCandidateDurationMs()));
        assertTrue(candidates.stream().allMatch(candidate -> candidate.score() < 1));
    }

    @Test
    void promotesGoalWithIndependentAudioReactionAndEstimatesBuildupBoundary() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "crosses into the box"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 50_000, 51_000, "scores the goal!")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>();
        observations.addAll(new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            boolean peak = index == 49 || index == 50;
            windows.add(new AudioEnergyWindow(500L + index * 1_000L, peak ? -10 : -30,
                    peak ? -8 : -28, peak ? 0.20 : 0.06,
                    peak ? 220 : 120, peak ? 18 : 4, peak ? 0.8 : 0.8));
        }
        observations.addAll(new AudioExcitementDetector(settings, quality)
                .detect(new AudioEnergyAnalysis(70_000, windows)));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 70_000, NOW);

        CandidateEvent goal = candidates.stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();
        assertEquals(CandidateEventStatus.DETECTED, goal.status());
        assertTrue(goal.liveEventProbability() >= quality.minimumLiveEventProbability());
        assertEquals(15_000, goal.startTimeMs());
        assertTrue(goal.endTimeMs() > goal.triggerTimestampMs());
        assertTrue(goal.endTimeMs() - goal.startTimeMs() <= quality.maximumCandidateDurationMs());
        assertTrue(goal.signals().stream()
                .anyMatch(signal -> signal.type() == CandidateSignalType.EVENT_BOUNDARY
                        && signal.evidence().contains("attack-buildup")));
    }

    @Test
    void retainsReplayGoalCandidateUntilAnExistingCanonicalEventCanBeMatched() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000,
                        "In the replay, he scores the goal.")
        });
        List<CandidateSignalObservation> observations = new ArrayList<>(
                new TranscriptSignalDetector(settings).detect(transcript));
        observations.addAll(new CandidateContextSignalDetector(quality).detect(transcript));

        CandidateEvent candidate = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).getFirst();

        assertEquals(CandidateEventStatus.REJECTED, candidate.status());
        assertTrue(candidate.replayProbability() >= quality.replayProbabilityThreshold());
        assertTrue(candidate.rejectionReasons().contains("REPLAY_COMMENTARY_REFERENCE"));
        assertTrue(candidate.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.REPLAY_CONTEXT
                        && signal.evidence().contains("contextual suppression applied")));
    }

    @Test
    void keepsSimilarNearbyGoalCommentaryWithoutExplicitReplayEvidenceAsSeparateCandidates() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "scores the goal"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 50_000, 51_000, "scores the goal")
        });
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(10_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                                FootballEventType.GOAL, 0.90, 10_000, "scores the goal"),
                        new CandidateSignal(CandidateSignalType.AUDIO_SPIKE,
                                null, 0.95, 10_000, "independent local audio spike"))),
                new CandidateSignalObservation(50_000, List.of(
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL,
                                FootballEventType.GOAL, 0.90, 50_000, "scores the goal"),
                        new CandidateSignal(CandidateSignalType.AUDIO_SPIKE,
                                null, 0.95, 50_000, "independent local audio spike"))));

        List<CandidateEvent> candidates = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 90_000, NOW);
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate ->
                candidate.status() == CandidateEventStatus.DETECTED));
    }

    @Test
    void identifiesTranscriptSpeechRateIncreaseAgainstLocalBaseline() {
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000, "and"),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000,
                        "the ball moves down the field"),
                TranscriptSegment.create(UUID.randomUUID(), 2, 38_000, 39_000,
                        "he crosses into the box now")
        });

        List<CandidateSignal> signals = new CandidateContextSignalDetector(quality).detect(transcript).stream()
                .flatMap(observation -> observation.signals().stream()).toList();

        assertTrue(signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.SPEECH_RATE_SPIKE));
        assertFalse(signals.stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.REPLAY_CONTEXT));
    }

    @Test
    void emitsPitchRiseVarianceVoiceAndCrowdProxySignals() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            boolean excited = index == 10;
            windows.add(new AudioEnergyWindow(500L + index * 1_000L,
                    excited ? -10 : -25, excited ? -8 : -23, excited ? 0.22 : 0.05,
                    excited ? 210 : 120, excited ? 55 : 4, 0.8));
        }

        List<CandidateSignal> signals = new AudioExcitementDetector(settings, quality)
                .detect(new AudioEnergyAnalysis(12_000, windows)).stream()
                .flatMap(observation -> observation.signals().stream()).toList();

        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.PITCH_RISE));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.PITCH_VARIANCE));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.VOICE_EXCITEMENT));
        assertTrue(signals.stream().anyMatch(signal -> signal.type() == CandidateSignalType.CROWD_REACTION_PROXY));
        assertTrue(signals.stream().filter(signal ->
                        signal.type() == CandidateSignalType.CROWD_REACTION_PROXY)
                .allMatch(signal -> signal.evidence().contains("not crowd or source identification")));
    }

    @Test
    void detectsRelativeEnergyRiseAndUsesItForBoundarySearch() {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            double dbfs = index == 4 ? -27 : index == 5 ? -23 : index == 6 ? -18 : -30;
            windows.add(new AudioEnergyWindow(500L + index * 1_000L, dbfs));
        }
        List<CandidateSignalObservation> audioObservations = new AudioExcitementDetector(settings, quality)
                .detect(new AudioEnergyAnalysis(12_000, windows));
        assertTrue(audioObservations.stream().flatMap(observation -> observation.signals().stream())
                .anyMatch(signal -> signal.type() == CandidateSignalType.AUDIO_ENERGY_RISE));

        Transcript transcript = transcript("en", new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 50_000, 51_000, "scores the goal")
        });
        CandidateSignal buildup = new CandidateSignal(CandidateSignalType.AUDIO_ENERGY_RISE,
                null, 0.75, 40_000, "RMS energy rose before the event");
        List<CandidateSignalObservation> observations = List.of(
                new CandidateSignalObservation(40_000, List.of(buildup)),
                new CandidateSignalObservation(50_000, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 50_000, "scores the goal"))));

        CandidateEvent candidate = new CandidateEventAssembler(settings, quality).assemble(
                transcript.getMediaAssetId(), transcript, observations, 90_000, NOW).getFirst();

        assertEquals(35_000, candidate.startTimeMs());
        assertTrue(candidate.signals().stream().anyMatch(signal ->
                signal.type() == CandidateSignalType.EVENT_BOUNDARY
                        && signal.evidence().contains("local RMS energy-rise cue")));
    }

    @Test
    void goalBoundaryUsesConnectedAttackAndShotBeforeTheScoringAction() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.ATTACK_BUILDUP, null,
                        0.82, 20_000, "crosses into the attacking third"),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        0.88, 45_000, "shot from inside the box"),
                audioReaction(50_000)), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 20_000, 21_000,
                        "Crosses into the attacking third."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 27_000, 28_000,
                        "The attack continues down the wing."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 34_000, 35_000,
                        "The attack is still building."),
                TranscriptSegment.create(UUID.randomUUID(), 3, 38_000, 39_000,
                        "The ball is sent into the area."),
                TranscriptSegment.create(UUID.randomUUID(), 4, 45_000, 46_000,
                        "A shot from inside the box."),
                goalTextSegment(50_000)
        });

        assertTrue(goal.startTimeMs() < 45_000);
        assertTrue(goal.endTimeMs() > 50_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY).contains("START=CONNECTED_ATTACK"));
    }

    @Test
    void goalWithoutReliableBuildupRetainsThirtySecondFallbackPreroll() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000,
                List.of(audioReaction(50_000)), new TranscriptSegment[] { goalTextSegment(50_000) });

        assertEquals(20_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY).contains("START=FALLBACK_PRE_ROLL"));
    }

    @Test
    void restartBoundaryPreventsAnEarlierAttackFromReplacingTheFallbackBoundary() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.ATTACK_BUILDUP, null,
                        0.82, 10_000, "Earlier attack without a connected shot"),
                new CandidateSignal(CandidateSignalType.RESTART_CONTEXT, null,
                        0.91, 30_000, "Play resumed in midfield")), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "An attack develops."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000, "Play resumes in midfield."),
                goalTextSegment(50_000)
        });

        assertEquals(20_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=FALLBACK_PRE_ROLL"));
    }

    @Test
    void replayBoundaryFencesOffEarlierAttackEvidence() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.ATTACK_BUILDUP, null,
                        0.82, 10_000, "Attack before a replay"),
                new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                        0.90, 30_000, "Replay introduction")), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 10_000, 11_000, "An attack develops."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 30_000, 31_000, "Let's see that again."),
                goalTextSegment(50_000)
        });

        assertEquals(20_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=FALLBACK_PRE_ROLL"));
    }

    @Test
    void audioOnlyBuildupUsesConnectedSpeechAndPitchTransitionsWhenAsrIsPoor() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.PITCH_RISE, null,
                        0.77, 35_000, "Local voiced pitch transition"),
                new CandidateSignal(CandidateSignalType.SPEECH_RATE_SPIKE, null,
                        0.74, 36_000, "Commentary speech rate increased"),
                new CandidateSignal(CandidateSignalType.AUDIO_ENERGY_RISE, null,
                        0.72, 38_000, "Local RMS energy increased"),
                audioReaction(50_000)), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 35_000, 36_000, "Unclear commentary."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 50_000, 51_000, "He scores the goal!")
        });

        assertEquals(30_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=AUDIO_BUILDUP"));
    }

    @Test
    void aSingleAudioExcitementFamilyDoesNotReplaceTheFallbackBoundary() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.PITCH_RISE, null,
                        0.77, 40_000, "Local voiced pitch transition"),
                new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT, null,
                        0.82, 40_000, "Voiced excitement")), new TranscriptSegment[] {
                goalTextSegment(50_000)
        });

        assertEquals(20_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=FALLBACK_PRE_ROLL"));
    }

    @Test
    void shotImmediatelyBeforeGoalProvidesAnActionBoundary() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        0.88, 48_000, "A shot immediately precedes the goal"),
                audioReaction(50_000)), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 48_000, 49_000, "A shot is taken."),
                goalTextSegment(50_000)
        });

        assertTrue(goal.startTimeMs() <= 48_000);
        assertTrue(goal.endTimeMs() > 50_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=DECISIVE_ACTION"));
    }

    @Test
    void replayIntroductionStopsReactionSignalsFromExtendingLiveGoalClip() {
        CandidateEvent goal = assembleBoundaryGoal(120_000, 50_000, List.of(
                audioReaction(50_000),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 0.88, 57_000, "Immediate reaction"),
                new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                        0.91, 65_000, "Replay introduction"),
                new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null,
                        0.97, 78_000, "Strong replay goal call")), new TranscriptSegment[] {
                goalTextSegment(50_000),
                TranscriptSegment.create(UUID.randomUUID(), 1, 57_000, 58_000, "What a finish!"),
                TranscriptSegment.create(UUID.randomUUID(), 2, 65_000, 66_000, "Let's see that again.")
        });

        assertTrue(goal.endTimeMs() <= 65_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_AFTERGLOW).contains("END=REPLAY_BOUNDARY"));
    }

    @Test
    void restartEndsGoalClipAfterImmediateReactionAndBeforeResumedPlay() {
        CandidateEvent goal = assembleBoundaryGoal(120_000, 50_000, List.of(
                audioReaction(50_000),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 0.88, 58_000, "Immediate reaction"),
                new CandidateSignal(CandidateSignalType.RESTART_CONTEXT, null,
                        0.90, 70_000, "Kickoff and match resumed")), new TranscriptSegment[] {
                goalTextSegment(50_000),
                TranscriptSegment.create(UUID.randomUUID(), 1, 58_000, 59_000, "The crowd is celebrating."),
                TranscriptSegment.create(UUID.randomUUID(), 2, 70_000, 71_000, "Kickoff, match is underway.")
        });

        assertTrue(goal.endTimeMs() <= 70_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_AFTERGLOW).contains("END=IMMEDIATE_RESTART"));
    }

    @Test
    void longPostGoalDiscussionDoesNotExtendTheImmediateReactionWindow() {
        CandidateEvent goal = assembleBoundaryGoal(120_000, 50_000, List.of(
                audioReaction(50_000),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 0.88, 58_000, "Immediate reaction"),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 0.94, 76_000, "Long-form analysis")), new TranscriptSegment[] {
                goalTextSegment(50_000),
                TranscriptSegment.create(UUID.randomUUID(), 1, 58_000, 59_000, "What a goal!"),
                TranscriptSegment.create(UUID.randomUUID(), 2, 76_000, 77_000,
                        "The analysis of that scoring action continues.")
        });

        assertEquals(66_000, goal.endTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_AFTERGLOW).contains("END=GOAL_REACTION"));
    }

    @Test
    void penaltySetupCanAnchorGoalClipBeforePenaltyKick() {
        CandidateEvent goal = assembleBoundaryGoal(100_000, 50_000, List.of(
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_PENALTY, FootballEventType.PENALTY,
                        0.91, 30_000, "Penalty awarded"),
                new CandidateSignal(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        0.84, 48_000, "Penalty kick"),
                audioReaction(50_000)), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000, "Penalty awarded."),
                TranscriptSegment.create(UUID.randomUUID(), 1, 48_000, 49_000, "The penalty is taken."),
                goalTextSegment(50_000)
        });

        assertTrue(goal.startTimeMs() <= 30_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY).contains("START=PENALTY_SETUP"));
    }

    @Test
    void explicitTranscriptPenaltyAttemptCanAnchorSetupWithoutAClassifiedPenaltySignal() {
        CandidateEvent goal = assembleBoundaryGoal(100_000, 50_000, List.of(
                audioReaction(50_000)), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 30_000, 31_000,
                        "Lanzamiento de penante."),
                goalTextSegment(50_000)
        });

        assertTrue(goal.startTimeMs() <= 30_000);
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY)
                .contains("START=PENALTY_SETUP"));
    }

    @Test
    void goalBoundariesClampAtMediaStartAndMediaEnd() {
        CandidateEvent atStart = assembleBoundaryGoal(20_000, 5_000,
                List.of(audioReaction(5_000)), new TranscriptSegment[] { goalTextSegment(5_000) });
        CandidateEvent nearEnd = assembleBoundaryGoal(100_000, 95_000,
                List.of(audioReaction(95_000)), new TranscriptSegment[] { goalTextSegment(95_000) });

        assertEquals(0, atStart.startTimeMs());
        assertTrue(atStart.endTimeMs() <= 20_000);
        assertTrue(nearEnd.startTimeMs() >= 0);
        assertTrue(nearEnd.startTimeMs() < nearEnd.endTimeMs());
        assertEquals(100_000, nearEnd.endTimeMs());
    }

    @Test
    void maximumDurationTrimsOlderContextWithoutTrimmingScoringAction() {
        CandidateEvent goal = assembleBoundaryGoal(120_000, 55_000, List.of(
                new CandidateSignal(CandidateSignalType.ATTACK_BUILDUP, null,
                        0.85, 15_000, "long attacking move"),
                audioReaction(55_000),
                new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null,
                        0.90, 80_000, "Immediate sustained crowd reaction")), new TranscriptSegment[] {
                TranscriptSegment.create(UUID.randomUUID(), 0, 15_000, 16_000, "Long attacking move."),
                goalTextSegment(55_000)
        });

        assertTrue(goal.startTimeMs() <= 55_000);
        assertTrue(goal.endTimeMs() >= 55_000);
        assertTrue(goal.endTimeMs() - goal.startTimeMs()
                <= CandidateDetectionQualitySettings.defaults().maximumCandidateDurationMs());
    }

    @Test
    void replayContextDoesNotBecomeGoalStartEvidence() {
        CandidateEvent goal = assembleBoundaryGoal(90_000, 50_000, List.of(
                audioReaction(50_000),
                new CandidateSignal(CandidateSignalType.REPLAY_CONTEXT, FootballEventType.GOAL,
                        0.90, 50_000, "Replay introduction")), new TranscriptSegment[] {
                goalTextSegment(50_000)
        });

        assertEquals(20_000, goal.startTimeMs());
        assertTrue(boundaryReason(goal, CandidateSignalType.EVENT_BOUNDARY).contains("START=FALLBACK_PRE_ROLL"));
    }

    @Test
    void veryShortAvailableMediaWindowUsesDeterministicClampedFallback() {
        CandidateEvent first = assembleBoundaryGoal(500, 500, List.of(audioReaction(500)),
                new TranscriptSegment[] { goalTextSegment(0) });
        CandidateEvent second = assembleBoundaryGoal(500, 500, List.of(audioReaction(500)),
                new TranscriptSegment[] { goalTextSegment(0) });

        assertEquals(0, first.startTimeMs());
        assertEquals(500, first.endTimeMs());
        assertEquals(first.startTimeMs(), second.startTimeMs());
        assertEquals(first.endTimeMs(), second.endTimeMs());
        assertTrue(boundaryReason(first, CandidateSignalType.EVENT_BOUNDARY).contains("START=FALLBACK_PRE_ROLL"));
    }

    private static CandidateEvent assembleBoundaryGoal(long durationMs, long goalTimeMs,
                                                       List<CandidateSignal> additionalSignals,
                                                       TranscriptSegment[] segments) {
        CandidateDetectionSettings settings = settings();
        CandidateDetectionQualitySettings quality = CandidateDetectionQualitySettings.defaults();
        Transcript transcript = Transcript.create(UUID.randomUUID(), "en", NOW);
        for (int index = 0; index < segments.length; index++) {
            TranscriptSegment segment = segments[index];
            transcript.addSegment(TranscriptSegment.create(transcript.getId(), index,
                    segment.startTimeMs(), segment.endTimeMs(), segment.text()));
        }
        List<CandidateSignal> signals = new ArrayList<>(additionalSignals);
        signals.add(new CandidateSignal(CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                0.96, goalTimeMs, "Football phrase match: scores the goal"));
        List<CandidateSignalObservation> observations = signals.stream()
                .collect(java.util.stream.Collectors.groupingBy(CandidateSignal::timestampMs))
                .entrySet().stream()
                .map(entry -> new CandidateSignalObservation(entry.getKey(), entry.getValue()))
                .toList();

        return new CandidateEventAssembler(settings, quality).assemble(
                        transcript.getMediaAssetId(), transcript, observations, durationMs, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.GOAL)
                .findFirst().orElseThrow();
    }

    private static CandidateSignal audioReaction(long timestampMs) {
        return new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null,
                0.92, timestampMs, "Independent local reaction audio");
    }

    private static TranscriptSegment goalTextSegment(long timestampMs) {
        return TranscriptSegment.create(UUID.randomUUID(), 0, timestampMs, timestampMs + 1_000,
                "He scores the goal!");
    }

    private static String boundaryReason(CandidateEvent candidate, CandidateSignalType type) {
        return candidate.signals().stream()
                .filter(signal -> signal.type() == type)
                .map(CandidateSignal::evidence)
                .findFirst().orElseThrow();
    }

    private static Transcript transcript(String language, TranscriptSegment[] segments) {
        UUID mediaAssetId = UUID.randomUUID();
        Transcript transcript = Transcript.create(mediaAssetId, language, NOW);
        for (TranscriptSegment segment : segments) {
            transcript.addSegment(TranscriptSegment.create(transcript.getId(), segment.sequence(),
                    segment.startTimeMs(), segment.endTimeMs(), segment.text()));
        }
        return transcript;
    }

    private static List<CandidateSignalObservation> combine(List<CandidateSignalObservation> first,
                                                             List<CandidateSignalObservation> second) {
        List<CandidateSignalObservation> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static CandidateEvent penaltyCandidate(CandidateDetectionSettings settings, Transcript transcript) {
        List<CandidateSignalObservation> observations = new TranscriptSignalDetector(settings).detect(transcript);
        return new CandidateEventAssembler(settings).assemble(
                transcript.getMediaAssetId(), transcript, observations, 60_000, NOW).stream()
                .filter(candidate -> candidate.eventType() == FootballEventType.PENALTY)
                .findFirst().orElseThrow();
    }

    private static List<CandidateSignal> scoreTransitions(CandidateDetectionQualitySettings quality,
                                                          String... scoreTexts) {
        TranscriptSegment[] segments = new TranscriptSegment[scoreTexts.length];
        for (int index = 0; index < scoreTexts.length; index++) {
            long start = 10_000L + index * 10_000L;
            segments[index] = TranscriptSegment.create(UUID.randomUUID(), index, start, start + 1_000,
                    scoreTexts[index]);
        }
        return new CandidateContextSignalDetector(quality).detect(transcript("en", segments)).stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.SCORE_STATE_TRANSITION)
                .toList();
    }

    private static CandidateSignalObservation signal(CandidateSignalType type, FootballEventType eventType,
                                                     double confidence, long timestampMs) {
        return new CandidateSignalObservation(timestampMs,
                List.of(new CandidateSignal(type, eventType, confidence, timestampMs,
                        "Test evidence for " + type)));
    }

    private static CandidateDetectionSettings settings() {
        Map<FootballEventType, List<String>> portuguese = Map.of(
                FootballEventType.GOAL, List.of("gol", "golaço"),
                FootballEventType.PENALTY, List.of("pênalti para"),
                FootballEventType.BIG_CHANCE, List.of("chance clara", "what a chance"));
        Map<FootballEventType, List<String>> english = Map.of(
                FootballEventType.GOAL, List.of("goal", "scores", "scored", "finds the net",
                        "it s gone in", "it has gone in", "he s found the net", "they ve found the net",
                        "into the back of the net", "makes it five", "makes it 5", "taps it in",
                        "own goal", "scores an own goal", "turns it into his own net",
                        "deflected into his own net"),
                FootballEventType.SHOT, List.of("strikes it", "strikes", "fires", "heads", "heads it",
                        "taps in", "finishes", "snatches in", "converts the chance"),
                FootballEventType.PENALTY, List.of("penalty awarded"),
                FootballEventType.FOUL, List.of("foul"),
                FootballEventType.BIG_CHANCE, List.of("what a chance"));
        Map<FootballEventType, List<String>> spanish = Map.of(
                FootballEventType.GOAL, List.of("golazo", "marca el gol", "anotó", "anota",
                        "transforma el primer tanto", "transforma el tanto", "abre el marcador",
                        "iguala el marcador", "empató", "igualó"),
                FootballEventType.PENALTY, List.of("penalti señalado", "penalti a favor",
                        "lanza el penalti", "convierte el penalti"),
                FootballEventType.ATTACK, List.of("ataque"));
        Map<FootballEventType, List<String>> french = Map.of(
                FootballEventType.GOAL, List.of("marque le but", "il a marqué", "a marqué",
                        "vient de marquer", "inscrit par", "égalisation",
                        "c'est au fond", "au fond des filets", "et c'est but"),
                FootballEventType.SHOT, List.of("frappe au but", "tir cadré", "tente sa chance", "frappe", "tir"),
                FootballEventType.PENALTY, List.of("penalty pour", "tire le penalty"),
                FootballEventType.YELLOW_CARD, List.of("carton jaune", "averti"),
                FootballEventType.RED_CARD, List.of("carton rouge", "expulsé"),
                FootballEventType.FOUL, List.of("faute"),
                FootballEventType.ATTACK, List.of("attaque", "offensive", "pressing",
                        "profondeur", "surface", "centre"));
        Map<FootballEventType, Double> confidence = new EnumMap<>(FootballEventType.class);
        confidence.put(FootballEventType.GOAL, 0.88);
        confidence.put(FootballEventType.BIG_CHANCE, 0.78);
        confidence.put(FootballEventType.SHOT, 0.82);
        confidence.put(FootballEventType.YELLOW_CARD, 0.83);
        confidence.put(FootballEventType.RED_CARD, 0.86);
        confidence.put(FootballEventType.ATTACK, 0.62);
        return new CandidateDetectionSettings(1_000, 10_000, 6, -45, 1_500,
                5_000, 8_000, 5_000, 0.50, 0.70, 10,
                20_000, 2, 8, 0.65, 0.70, 0.30, 0.10,
                Map.of("pt", portuguese, "en", english, "es", spanish, "fr", french), confidence,
                Map.of("en", List.of("own goal", "scores an own goal", "turns it into his own net",
                        "deflected into his own net")));
    }

    private static AudioEnergyAnalysis sustainedGoalCallAudio() {
        List<AudioEnergyWindow> windows = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            boolean excited = index >= 11;
            windows.add(new AudioEnergyWindow(3_245_500L + index * 1_000L,
                    excited ? -23 : -30, excited ? -20 : -28, excited ? 0.10 : 0.07,
                    excited ? 360 : 180, excited ? 80 : 5, 0.67));
        }
        return new AudioEnergyAnalysis(3_270_000, windows);
    }
}
