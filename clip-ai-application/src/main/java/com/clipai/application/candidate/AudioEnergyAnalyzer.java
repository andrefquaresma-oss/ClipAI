package com.clipai.application.candidate;

import java.nio.file.Path;
import java.util.List;

public interface AudioEnergyAnalyzer {
    AudioEnergyAnalysis analyze(Path wavPath, long windowDurationMs);

    record AudioEnergyAnalysis(long durationMs, List<AudioEnergyWindow> windows) {
        public AudioEnergyAnalysis {
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            windows = List.copyOf(windows);
            long previousTimestamp = -1;
            for (AudioEnergyWindow window : windows) {
                if (window.timestampMs() <= previousTimestamp || window.timestampMs() > durationMs) {
                    throw new IllegalArgumentException("audio windows must be ordered within the analyzed duration");
                }
                previousTimestamp = window.timestampMs();
            }
        }
    }

    record AudioEnergyWindow(long timestampMs, double dbfs, double peakDbfs,
                             double zeroCrossingRate, double medianPitchHz,
                             double pitchVarianceHz, double voicedFrameRatio) {
        public AudioEnergyWindow(long timestampMs, double dbfs) {
            this(timestampMs, dbfs, dbfs, 0, 0, 0, 0);
        }

        public AudioEnergyWindow {
            if (timestampMs < 0) {
                throw new IllegalArgumentException("timestampMs must not be negative");
            }
            if (!Double.isFinite(dbfs) || dbfs > 0) {
                throw new IllegalArgumentException("dbfs must be finite and no greater than zero");
            }
            if (!Double.isFinite(peakDbfs) || peakDbfs > 0) {
                throw new IllegalArgumentException("peakDbfs must be finite and no greater than zero");
            }
            if (!Double.isFinite(zeroCrossingRate) || zeroCrossingRate < 0 || zeroCrossingRate > 1
                    || !Double.isFinite(medianPitchHz) || medianPitchHz < 0
                    || !Double.isFinite(pitchVarianceHz) || pitchVarianceHz < 0
                    || !Double.isFinite(voicedFrameRatio) || voicedFrameRatio < 0 || voicedFrameRatio > 1) {
                throw new IllegalArgumentException("audio feature values are outside their valid ranges");
            }
        }
    }
}
