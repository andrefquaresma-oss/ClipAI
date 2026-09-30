package com.clipai.application.candidate;

import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyAnalysis;
import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyWindow;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AudioExcitementDetector {
    private final CandidateDetectionSettings settings;
    private final CandidateDetectionQualitySettings qualitySettings;

    public AudioExcitementDetector(CandidateDetectionSettings settings) {
        this(settings, CandidateDetectionQualitySettings.defaults());
    }

    public AudioExcitementDetector(CandidateDetectionSettings settings,
                                  CandidateDetectionQualitySettings qualitySettings) {
        this.settings = settings;
        this.qualitySettings = qualitySettings;
    }

    public List<CandidateSignalObservation> detect(AudioEnergyAnalysis analysis) {
        List<AudioEnergyWindow> windows = analysis.windows();
        boolean[] spikes = new boolean[windows.size()];
        double[] excessDb = new double[windows.size()];
        List<CandidateSignalObservation> observations = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            AudioEnergyWindow current = windows.get(index);
            double baseline = baselineFor(windows, index);
            excessDb[index] = current.dbfs() - baseline;
            spikes[index] = current.dbfs() >= settings.audioMinimumDb()
                    && excessDb[index] >= settings.audioSpikeThresholdDb();
            if (index >= 3 && Double.isFinite(baseline)) {
                double risingDb = current.dbfs() - windows.get(index - 3).dbfs();
                if (risingDb >= settings.audioSpikeThresholdDb()
                        && excessDb[index] >= settings.audioSpikeThresholdDb() / 2
                        && current.dbfs() >= settings.audioMinimumDb()) {
                    CandidateSignal rise = new CandidateSignal(CandidateSignalType.AUDIO_ENERGY_RISE,
                            null, Math.min(0.88, 0.58 + (risingDb - settings.audioSpikeThresholdDb()) / 35.0),
                            current.timestampMs(),
                            String.format(java.util.Locale.ROOT,
                                    "RMS energy rose %.1f dB across the preceding local audio windows",
                                    risingDb));
                    observations.add(new CandidateSignalObservation(current.timestampMs(), List.of(rise)));
                }
            }
        }

        for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
            AudioEnergyWindow window = windows.get(windowIndex);
            if (window.voicedFrameRatio() < qualitySettings.minimumVoicedFrameRatio()
                    || window.medianPitchHz() <= 0) {
                continue;
            }
            double pitchBaseline = pitchBaselineFor(windows, windowIndex);
            boolean pitchRise = pitchBaseline > 0
                    && window.medianPitchHz() >= pitchBaseline * qualitySettings.pitchRiseRatio();
            boolean pitchVariance = window.pitchVarianceHz() / window.medianPitchHz()
                    >= qualitySettings.pitchVarianceRatio();
            if (pitchRise) {
                double ratio = window.medianPitchHz() / pitchBaseline;
                double confidence = Math.min(0.94, 0.62 + (ratio - qualitySettings.pitchRiseRatio()) * 0.35);
                CandidateSignal pitchSignal = new CandidateSignal(CandidateSignalType.PITCH_RISE,
                        null, confidence, window.timestampMs(),
                        String.format(java.util.Locale.ROOT,
                                "Estimated voiced median pitch rose from %.0f Hz to %.0f Hz (%.2fx local baseline)",
                                pitchBaseline, window.medianPitchHz(), ratio));
                observations.add(new CandidateSignalObservation(window.timestampMs(), List.of(pitchSignal)));
            }

            if (pitchVariance) {
                CandidateSignal varianceSignal = new CandidateSignal(CandidateSignalType.PITCH_VARIANCE,
                        null, Math.min(0.90, 0.58 + window.pitchVarianceHz()
                        / window.medianPitchHz() * 0.35), window.timestampMs(),
                        String.format(java.util.Locale.ROOT,
                                "Voiced median pitch varied by %.1f Hz within the analysis window",
                                window.pitchVarianceHz()));
                observations.add(new CandidateSignalObservation(window.timestampMs(), List.of(varianceSignal)));
            }

            double relativePeak = window.peakDbfs() - baselineFor(windows, windowIndex);
            if (pitchRise || pitchVariance
                    || (spikes[windowIndex] && relativePeak >= settings.audioSpikeThresholdDb())) {
                double voiceConfidence = Math.min(0.95, 0.55
                        + Math.max(0, relativePeak) / 45.0
                        + (pitchRise ? 0.12 : 0));
                CandidateSignal voiceSignal = new CandidateSignal(CandidateSignalType.VOICE_EXCITEMENT,
                        null, voiceConfidence, window.timestampMs(),
                        "Voiced audio combined local energy and pitch features; speaker identity is not inferred");
                observations.add(new CandidateSignalObservation(window.timestampMs(), List.of(voiceSignal)));
            }
        }

        int index = 0;
        while (index < spikes.length) {
            if (!spikes[index]) {
                index++;
                continue;
            }
            int first = index;
            int peak = index;
            int last = index;
            while (last + 1 < spikes.length && spikes[last + 1]) {
                last++;
                if (excessDb[last] > excessDb[peak]) {
                    peak = last;
                }
            }

            AudioEnergyWindow peakWindow = windows.get(peak);
            double peakConfidence = Math.min(0.99, 0.55
                    + Math.max(0, excessDb[peak] - settings.audioSpikeThresholdDb()) / 25.0);
            long activeDurationMs = Math.min(analysis.durationMs(),
                    windows.get(last).timestampMs() + settings.audioWindowMs() - windows.get(first).timestampMs());
            List<CandidateSignal> signals = new ArrayList<>();
            signals.add(new CandidateSignal(CandidateSignalType.AUDIO_SPIKE, null, peakConfidence,
                    peakWindow.timestampMs(), String.format(java.util.Locale.ROOT,
                            "Audio energy was %.1f dB above the local baseline for %d ms",
                            excessDb[peak], activeDurationMs)));
            if (activeDurationMs >= settings.audioSustainedThresholdMs()) {
                double sustainedConfidence = Math.min(0.97, 0.55 + activeDurationMs / 40_000.0);
                signals.add(new CandidateSignal(CandidateSignalType.AUDIO_SUSTAINED,
                        null, sustainedConfidence, peakWindow.timestampMs(),
                        "Sustained audio intensity exceeded the configured duration; source is not classified"));
            }
            observations.add(new CandidateSignalObservation(peakWindow.timestampMs(), signals));
            index = last + 1;
        }
        for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
            AudioEnergyWindow window = windows.get(windowIndex);
            if (spikes[windowIndex]
                    && window.zeroCrossingRate() >= qualitySettings.crowdZeroCrossingThreshold()) {
                CandidateSignal crowdProxy = new CandidateSignal(CandidateSignalType.CROWD_REACTION_PROXY,
                        null, Math.min(0.86, 0.55 + excessDb[windowIndex] / 50.0),
                        window.timestampMs(),
                        "Broadband audio-energy change is a crowd-reaction proxy, not crowd or source identification");
                observations.add(new CandidateSignalObservation(window.timestampMs(), List.of(crowdProxy)));
            }
        }
        addSustainedVoiceExcitement(observations);
        return List.copyOf(observations);
    }

    private void addSustainedVoiceExcitement(List<CandidateSignalObservation> observations) {
        List<CandidateSignal> voiceSignals = observations.stream()
                .flatMap(observation -> observation.signals().stream())
                .filter(signal -> signal.type() == CandidateSignalType.VOICE_EXCITEMENT)
                .sorted(Comparator.comparingLong(CandidateSignal::timestampMs))
                .toList();
        long maximumGapMs = settings.audioWindowMs() * 2;
        long minimumDurationMs = Math.max(3_000, settings.audioSustainedThresholdMs());
        int first = 0;
        while (first < voiceSignals.size()) {
            int last = first;
            while (last + 1 < voiceSignals.size()
                    && voiceSignals.get(last + 1).timestampMs() - voiceSignals.get(last).timestampMs()
                    <= maximumGapMs) {
                last++;
            }
            CandidateSignal start = voiceSignals.get(first);
            CandidateSignal end = voiceSignals.get(last);
            long durationMs = end.timestampMs() - start.timestampMs() + settings.audioWindowMs();
            double confidence = voiceSignals.subList(first, last + 1).stream()
                    .mapToDouble(CandidateSignal::confidence)
                    .max().orElse(0);
            if (durationMs >= minimumDurationMs && confidence >= 0.72) {
                String featureEvidence = observations.stream()
                        .flatMap(observation -> observation.signals().stream())
                        .filter(signal -> signal.timestampMs() >= start.timestampMs()
                                && signal.timestampMs() <= end.timestampMs())
                        .map(signal -> signal.type().name())
                        .filter(type -> type.equals(CandidateSignalType.PITCH_RISE.name())
                                || type.equals(CandidateSignalType.PITCH_VARIANCE.name())
                                || type.equals(CandidateSignalType.AUDIO_SPIKE.name())
                                || type.equals(CandidateSignalType.AUDIO_SUSTAINED.name()))
                        .distinct()
                        .sorted()
                        .toList()
                        .toString();
                CandidateSignal highExcitement = new CandidateSignal(CandidateSignalType.HIGH_EXCITEMENT,
                        null, confidence, start.timestampMs(),
                        "Sustained voiced excitement lasted " + durationMs
                                + " ms; supporting audio features=" + featureEvidence);
                observations.add(new CandidateSignalObservation(start.timestampMs(), List.of(highExcitement)));
            }
            first = last + 1;
        }
    }

    private double pitchBaselineFor(List<AudioEnergyWindow> windows, int currentIndex) {
        long minimumTime = windows.get(currentIndex).timestampMs() - settings.audioBaselineWindowMs() / 2;
        List<Double> pitches = new ArrayList<>();
        for (int index = currentIndex - 1; index >= 0 && pitches.size() < 15; index--) {
            AudioEnergyWindow prior = windows.get(index);
            if (prior.timestampMs() < minimumTime) {
                break;
            }
            if (prior.voicedFrameRatio() >= qualitySettings.minimumVoicedFrameRatio()
                    && prior.medianPitchHz() > 0) {
                pitches.add(prior.medianPitchHz());
            }
        }
        if (pitches.isEmpty()) {
            return 0;
        }
        pitches.sort(Comparator.naturalOrder());
        return pitches.get(pitches.size() / 2);
    }

    private double baselineFor(List<AudioEnergyWindow> windows, int currentIndex) {
        AudioEnergyWindow current = windows.get(currentIndex);
        List<Double> baseline = new ArrayList<>();
        long exclusionDistance = settings.audioWindowMs();
        long maximumDistance = settings.audioBaselineWindowMs() / 2;
        long radius = Math.min(Integer.MAX_VALUE, maximumDistance / settings.audioWindowMs() + 1);
        int firstIndex = (int) Math.max(0, currentIndex - radius);
        int lastIndex = (int) Math.min(windows.size() - 1L, currentIndex + radius);
        for (int index = firstIndex; index <= lastIndex; index++) {
            long distance = Math.abs(windows.get(index).timestampMs() - current.timestampMs());
            if (distance > exclusionDistance && distance <= maximumDistance) {
                baseline.add(windows.get(index).dbfs());
            }
        }
        if (baseline.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        baseline.sort(Comparator.naturalOrder());
        int middle = baseline.size() / 2;
        return baseline.size() % 2 == 0
                ? (baseline.get(middle - 1) + baseline.get(middle)) / 2.0
                : baseline.get(middle);
    }
}
