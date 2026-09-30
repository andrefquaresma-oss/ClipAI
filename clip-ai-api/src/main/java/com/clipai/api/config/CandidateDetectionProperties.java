package com.clipai.api.config;

import com.clipai.application.candidate.CandidateDetectionSettings;
import com.clipai.application.candidate.CandidateDetectionQualitySettings;
import com.clipai.application.candidate.CandidateEventClusteringSettings;
import com.clipai.domain.candidate.FootballEventType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "clip-ai.candidate-detection")
public class CandidateDetectionProperties {
    private long audioWindowMs = 1000;
    private long audioBaselineWindowMs = 30000;
    private double audioSpikeThresholdDb = 9;
    private double audioMinimumDb = -45;
    private long audioSustainedThresholdMs = 1500;
    private long preEventPaddingMs = 5000;
    private long postEventPaddingMs = 8000;
    private long mergeDistanceMs = 5000;
    private double minimumCandidateScore = 0.52;
    private double singleSignalConfidenceThreshold = 0.80;
    private int maximumCandidates = 100;
    private long repeatedPhraseWindowMs = 20000;
    private int repeatedPhraseMinimumSegments = 2;
    private int transcriptEmphasisMinimumLetters = 8;
    private double transcriptEmphasisUppercaseRatio = 0.65;
    private double transcriptWeight = 0.70;
    private double audioWeight = 0.30;
    private double signalAgreementBonus = 0.10;
    private long speechRateWindowMs = 10000;
    private double speechRateSpikeMultiplier = 1.8;
    private int minimumWordsPerSpeechWindow = 8;
    private double pitchRiseRatio = 1.25;
    private double pitchVarianceRatio = 0.18;
    private double minimumVoicedFrameRatio = 0.34;
    private double minimumLiveEventProbability = 0.55;
    private double replayProbabilityThreshold = 0.68;
    private long replayTemporalWindowMs = 120000;
    private long contextAttachWindowMs = 8000;
    private long boundarySearchBackMs = 90000;
    private long attackBuildupMaximumLeadMs = 45000;
    private long goalFallbackPreRollMs = 30000;
    private double crowdZeroCrossingThreshold = 0.12;
    private long maximumCandidateDurationMs = 60000;
    private double minimumGoalReactionConfidence = 0.45;
    private double replayPenaltyWeight = 0.40;
    private double goalNegativeContextPenaltyWeight = 0.45;
    private long clusteringGoalTriggerWindowMs = 15000;
    private long clusteringCardTriggerWindowMs = 20000;
    private long clusteringShotTriggerWindowMs = 3000;
    private long clusteringOtherTriggerWindowMs = 10000;
    private double clusteringMinimumContextSimilarity = 0.48;
    private long clusteringMaximumEventDurationMs = 60000;
    private Map<String, Map<FootballEventType, List<String>>> lexicon = new LinkedHashMap<>();
    private Map<FootballEventType, Double> eventConfidence = new LinkedHashMap<>();
    private Map<String, List<String>> ownGoalPhrases = new LinkedHashMap<>();

    public CandidateDetectionSettings toSettings() {
        return new CandidateDetectionSettings(audioWindowMs, audioBaselineWindowMs, audioSpikeThresholdDb,
                audioMinimumDb, audioSustainedThresholdMs, preEventPaddingMs, postEventPaddingMs,
                mergeDistanceMs, minimumCandidateScore, singleSignalConfidenceThreshold, maximumCandidates,
                repeatedPhraseWindowMs, repeatedPhraseMinimumSegments, transcriptEmphasisMinimumLetters,
                transcriptEmphasisUppercaseRatio, transcriptWeight, audioWeight, signalAgreementBonus,
                lexicon, eventConfidence, ownGoalPhrases);
    }

    public CandidateDetectionQualitySettings toQualitySettings() {
        return new CandidateDetectionQualitySettings(speechRateWindowMs, speechRateSpikeMultiplier,
                minimumWordsPerSpeechWindow, pitchRiseRatio, pitchVarianceRatio, minimumVoicedFrameRatio,
                minimumLiveEventProbability, replayProbabilityThreshold, replayTemporalWindowMs,
                contextAttachWindowMs, boundarySearchBackMs, attackBuildupMaximumLeadMs,
                goalFallbackPreRollMs, crowdZeroCrossingThreshold,
                maximumCandidateDurationMs, minimumGoalReactionConfidence, replayPenaltyWeight,
                goalNegativeContextPenaltyWeight);
    }

    public CandidateEventClusteringSettings toEventClusteringSettings() {
        return new CandidateEventClusteringSettings(clusteringGoalTriggerWindowMs,
                clusteringCardTriggerWindowMs, clusteringShotTriggerWindowMs,
                clusteringOtherTriggerWindowMs, clusteringMinimumContextSimilarity,
                clusteringMaximumEventDurationMs);
    }

    public long getAudioWindowMs() { return audioWindowMs; }
    public void setAudioWindowMs(long audioWindowMs) { this.audioWindowMs = audioWindowMs; }
    public long getAudioBaselineWindowMs() { return audioBaselineWindowMs; }
    public void setAudioBaselineWindowMs(long audioBaselineWindowMs) { this.audioBaselineWindowMs = audioBaselineWindowMs; }
    public double getAudioSpikeThresholdDb() { return audioSpikeThresholdDb; }
    public void setAudioSpikeThresholdDb(double audioSpikeThresholdDb) { this.audioSpikeThresholdDb = audioSpikeThresholdDb; }
    public double getAudioMinimumDb() { return audioMinimumDb; }
    public void setAudioMinimumDb(double audioMinimumDb) { this.audioMinimumDb = audioMinimumDb; }
    public long getAudioSustainedThresholdMs() { return audioSustainedThresholdMs; }
    public void setAudioSustainedThresholdMs(long audioSustainedThresholdMs) { this.audioSustainedThresholdMs = audioSustainedThresholdMs; }
    public long getPreEventPaddingMs() { return preEventPaddingMs; }
    public void setPreEventPaddingMs(long preEventPaddingMs) { this.preEventPaddingMs = preEventPaddingMs; }
    public long getPostEventPaddingMs() { return postEventPaddingMs; }
    public void setPostEventPaddingMs(long postEventPaddingMs) { this.postEventPaddingMs = postEventPaddingMs; }
    public long getMergeDistanceMs() { return mergeDistanceMs; }
    public void setMergeDistanceMs(long mergeDistanceMs) { this.mergeDistanceMs = mergeDistanceMs; }
    public double getMinimumCandidateScore() { return minimumCandidateScore; }
    public void setMinimumCandidateScore(double minimumCandidateScore) { this.minimumCandidateScore = minimumCandidateScore; }
    public double getSingleSignalConfidenceThreshold() { return singleSignalConfidenceThreshold; }
    public void setSingleSignalConfidenceThreshold(double singleSignalConfidenceThreshold) { this.singleSignalConfidenceThreshold = singleSignalConfidenceThreshold; }
    public int getMaximumCandidates() { return maximumCandidates; }
    public void setMaximumCandidates(int maximumCandidates) { this.maximumCandidates = maximumCandidates; }
    public long getRepeatedPhraseWindowMs() { return repeatedPhraseWindowMs; }
    public void setRepeatedPhraseWindowMs(long repeatedPhraseWindowMs) { this.repeatedPhraseWindowMs = repeatedPhraseWindowMs; }
    public int getRepeatedPhraseMinimumSegments() { return repeatedPhraseMinimumSegments; }
    public void setRepeatedPhraseMinimumSegments(int repeatedPhraseMinimumSegments) { this.repeatedPhraseMinimumSegments = repeatedPhraseMinimumSegments; }
    public int getTranscriptEmphasisMinimumLetters() { return transcriptEmphasisMinimumLetters; }
    public void setTranscriptEmphasisMinimumLetters(int transcriptEmphasisMinimumLetters) { this.transcriptEmphasisMinimumLetters = transcriptEmphasisMinimumLetters; }
    public double getTranscriptEmphasisUppercaseRatio() { return transcriptEmphasisUppercaseRatio; }
    public void setTranscriptEmphasisUppercaseRatio(double transcriptEmphasisUppercaseRatio) { this.transcriptEmphasisUppercaseRatio = transcriptEmphasisUppercaseRatio; }
    public double getTranscriptWeight() { return transcriptWeight; }
    public void setTranscriptWeight(double transcriptWeight) { this.transcriptWeight = transcriptWeight; }
    public double getAudioWeight() { return audioWeight; }
    public void setAudioWeight(double audioWeight) { this.audioWeight = audioWeight; }
    public double getSignalAgreementBonus() { return signalAgreementBonus; }
    public void setSignalAgreementBonus(double signalAgreementBonus) { this.signalAgreementBonus = signalAgreementBonus; }
    public long getSpeechRateWindowMs() { return speechRateWindowMs; }
    public void setSpeechRateWindowMs(long speechRateWindowMs) { this.speechRateWindowMs = speechRateWindowMs; }
    public double getSpeechRateSpikeMultiplier() { return speechRateSpikeMultiplier; }
    public void setSpeechRateSpikeMultiplier(double speechRateSpikeMultiplier) { this.speechRateSpikeMultiplier = speechRateSpikeMultiplier; }
    public int getMinimumWordsPerSpeechWindow() { return minimumWordsPerSpeechWindow; }
    public void setMinimumWordsPerSpeechWindow(int minimumWordsPerSpeechWindow) { this.minimumWordsPerSpeechWindow = minimumWordsPerSpeechWindow; }
    public double getPitchRiseRatio() { return pitchRiseRatio; }
    public void setPitchRiseRatio(double pitchRiseRatio) { this.pitchRiseRatio = pitchRiseRatio; }
    public double getPitchVarianceRatio() { return pitchVarianceRatio; }
    public void setPitchVarianceRatio(double pitchVarianceRatio) { this.pitchVarianceRatio = pitchVarianceRatio; }
    public double getMinimumVoicedFrameRatio() { return minimumVoicedFrameRatio; }
    public void setMinimumVoicedFrameRatio(double minimumVoicedFrameRatio) { this.minimumVoicedFrameRatio = minimumVoicedFrameRatio; }
    public double getMinimumLiveEventProbability() { return minimumLiveEventProbability; }
    public void setMinimumLiveEventProbability(double minimumLiveEventProbability) { this.minimumLiveEventProbability = minimumLiveEventProbability; }
    public double getReplayProbabilityThreshold() { return replayProbabilityThreshold; }
    public void setReplayProbabilityThreshold(double replayProbabilityThreshold) { this.replayProbabilityThreshold = replayProbabilityThreshold; }
    public long getReplayTemporalWindowMs() { return replayTemporalWindowMs; }
    public void setReplayTemporalWindowMs(long replayTemporalWindowMs) { this.replayTemporalWindowMs = replayTemporalWindowMs; }
    public long getContextAttachWindowMs() { return contextAttachWindowMs; }
    public void setContextAttachWindowMs(long contextAttachWindowMs) { this.contextAttachWindowMs = contextAttachWindowMs; }
    public long getBoundarySearchBackMs() { return boundarySearchBackMs; }
    public void setBoundarySearchBackMs(long boundarySearchBackMs) { this.boundarySearchBackMs = boundarySearchBackMs; }
    public long getAttackBuildupMaximumLeadMs() { return attackBuildupMaximumLeadMs; }
    public void setAttackBuildupMaximumLeadMs(long attackBuildupMaximumLeadMs) { this.attackBuildupMaximumLeadMs = attackBuildupMaximumLeadMs; }
    public long getGoalFallbackPreRollMs() { return goalFallbackPreRollMs; }
    public void setGoalFallbackPreRollMs(long goalFallbackPreRollMs) { this.goalFallbackPreRollMs = goalFallbackPreRollMs; }
    public double getCrowdZeroCrossingThreshold() { return crowdZeroCrossingThreshold; }
    public void setCrowdZeroCrossingThreshold(double crowdZeroCrossingThreshold) { this.crowdZeroCrossingThreshold = crowdZeroCrossingThreshold; }
    public long getMaximumCandidateDurationMs() { return maximumCandidateDurationMs; }
    public void setMaximumCandidateDurationMs(long maximumCandidateDurationMs) { this.maximumCandidateDurationMs = maximumCandidateDurationMs; }
    public double getMinimumGoalReactionConfidence() { return minimumGoalReactionConfidence; }
    public void setMinimumGoalReactionConfidence(double minimumGoalReactionConfidence) { this.minimumGoalReactionConfidence = minimumGoalReactionConfidence; }
    public double getReplayPenaltyWeight() { return replayPenaltyWeight; }
    public void setReplayPenaltyWeight(double replayPenaltyWeight) { this.replayPenaltyWeight = replayPenaltyWeight; }
    public double getGoalNegativeContextPenaltyWeight() { return goalNegativeContextPenaltyWeight; }
    public void setGoalNegativeContextPenaltyWeight(double value) {
        this.goalNegativeContextPenaltyWeight = value;
    }
    public long getClusteringGoalTriggerWindowMs() { return clusteringGoalTriggerWindowMs; }
    public void setClusteringGoalTriggerWindowMs(long value) { this.clusteringGoalTriggerWindowMs = value; }
    public long getClusteringCardTriggerWindowMs() { return clusteringCardTriggerWindowMs; }
    public void setClusteringCardTriggerWindowMs(long value) { this.clusteringCardTriggerWindowMs = value; }
    public long getClusteringShotTriggerWindowMs() { return clusteringShotTriggerWindowMs; }
    public void setClusteringShotTriggerWindowMs(long value) { this.clusteringShotTriggerWindowMs = value; }
    public long getClusteringOtherTriggerWindowMs() { return clusteringOtherTriggerWindowMs; }
    public void setClusteringOtherTriggerWindowMs(long value) { this.clusteringOtherTriggerWindowMs = value; }
    public double getClusteringMinimumContextSimilarity() { return clusteringMinimumContextSimilarity; }
    public void setClusteringMinimumContextSimilarity(double value) {
        this.clusteringMinimumContextSimilarity = value;
    }
    public long getClusteringMaximumEventDurationMs() { return clusteringMaximumEventDurationMs; }
    public void setClusteringMaximumEventDurationMs(long value) {
        this.clusteringMaximumEventDurationMs = value;
    }
    public Map<String, Map<FootballEventType, List<String>>> getLexicon() { return lexicon; }
    public void setLexicon(Map<String, Map<FootballEventType, List<String>>> lexicon) { this.lexicon = lexicon; }
    public Map<FootballEventType, Double> getEventConfidence() { return eventConfidence; }
    public void setEventConfidence(Map<FootballEventType, Double> eventConfidence) { this.eventConfidence = eventConfidence; }
    public Map<String, List<String>> getOwnGoalPhrases() { return ownGoalPhrases; }
    public void setOwnGoalPhrases(Map<String, List<String>> ownGoalPhrases) {
        this.ownGoalPhrases = ownGoalPhrases;
    }
}
