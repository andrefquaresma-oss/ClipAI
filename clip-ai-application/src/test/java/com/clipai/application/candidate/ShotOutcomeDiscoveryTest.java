package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShotOutcomeDiscoveryTest {
    @Test
    void explicitOutcomeEvidenceDoesNotSuppressAnOtherwiseSupportedGoalHypothesis() {
        CandidateGoalDiscovery discovery = new CandidateGoalDiscovery(
                CandidateDetectionQualitySettings.defaults());
        List<CandidateSignalObservation> evidence = List.of(
                observation(CandidateSignalType.ATTACK_BUILDUP, null, 10_000, 0.8, "Attack builds"),
                observation(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        20_000, 0.85, "Shot"),
                observation(CandidateSignalType.AUDIO_SPIKE, null, 21_000, 0.9, "Reaction"),
                observation(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 22_000, 0.82, "Commentary"),
                observation(CandidateSignalType.SHOT_OUTCOME_CONTEXT, null, 21_500, 0.78,
                        "OUTCOME=CORNER; transcript phrase match: corner to"));

        assertFalse(discovery.discover(evidence).isEmpty());
    }

    @Test
    void retainsInferredGoalWhenNoNegativeOutcomeIsPresent() {
        CandidateGoalDiscovery discovery = new CandidateGoalDiscovery(
                CandidateDetectionQualitySettings.defaults());
        List<CandidateSignalObservation> evidence = List.of(
                observation(CandidateSignalType.ATTACK_BUILDUP, null, 10_000, 0.8, "Attack builds"),
                observation(CandidateSignalType.TRANSCRIPT_SHOT, FootballEventType.SHOT,
                        20_000, 0.85, "Shot"),
                observation(CandidateSignalType.AUDIO_SPIKE, null, 21_000, 0.9, "Reaction"),
                observation(CandidateSignalType.TRANSCRIPT_EMPHASIS,
                        FootballEventType.COMMENTATOR_REACTION, 22_000, 0.82, "Commentary"));

        assertFalse(discovery.discover(evidence).isEmpty());
    }

    private static CandidateSignalObservation observation(CandidateSignalType type,
                                                           FootballEventType eventType,
                                                           long timestamp, double confidence,
                                                           String evidence) {
        return new CandidateSignalObservation(timestamp, List.of(
                new CandidateSignal(type, eventType, confidence, timestamp, evidence)));
    }
}
