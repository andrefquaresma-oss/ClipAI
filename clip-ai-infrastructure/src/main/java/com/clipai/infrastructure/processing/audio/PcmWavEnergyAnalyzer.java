package com.clipai.infrastructure.processing.audio;

import com.clipai.application.candidate.AudioEnergyAnalyzer;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PcmWavEnergyAnalyzer implements AudioEnergyAnalyzer {
    private static final double SILENCE_FLOOR = 1.0e-5;

    @Override
    public AudioEnergyAnalysis analyze(Path wavPath, long windowDurationMs) {
        if (windowDurationMs <= 0) {
            throw new IllegalArgumentException("windowDurationMs must be positive");
        }
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            AudioFormat format = audio.getFormat();
            validateFormat(format);
            long framesPerWindow = Math.max(1L,
                    Math.round(format.getFrameRate() * windowDurationMs / 1000.0));
            int windowFrameCapacity = Math.toIntExact(framesPerWindow);
            int frameSize = format.getFrameSize();
            int bufferFrames = (int) Math.min(framesPerWindow, Math.max(1, 65_536 / frameSize));
            byte[] buffer = new byte[bufferFrames * frameSize];
            float[] monoSamples = new float[windowFrameCapacity];
            List<AudioEnergyWindow> windows = new ArrayList<>();
            long totalFrames = 0;

            while (true) {
                long remainingFrames = framesPerWindow;
                long windowFrames = 0;
                double squaredAmplitude = 0;
                while (remainingFrames > 0) {
                    int requestedFrames = (int) Math.min(remainingFrames, bufferFrames);
                    int requestedBytes = requestedFrames * frameSize;
                    int bytesRead = readFrames(audio, buffer, requestedBytes, frameSize);
                    if (bytesRead == -1) {
                        break;
                    }
                    int framesRead = bytesRead / frameSize;
                    for (int frame = 0; frame < framesRead; frame++) {
                        double frameSquaredAmplitude = 0;
                        double monoAmplitude = 0;
                        int frameOffset = frame * frameSize;
                        for (int channel = 0; channel < format.getChannels(); channel++) {
                            int sampleOffset = frameOffset + channel * (format.getSampleSizeInBits() / 8);
                            double sample = normalizedSample(buffer, sampleOffset, format);
                            frameSquaredAmplitude += sample * sample;
                            monoAmplitude += sample;
                        }
                        squaredAmplitude += frameSquaredAmplitude / format.getChannels();
                        monoSamples[Math.toIntExact(windowFrames + frame)] =
                                (float) (monoAmplitude / format.getChannels());
                    }
                    windowFrames += framesRead;
                    totalFrames += framesRead;
                    remainingFrames -= framesRead;
                    if (framesRead < requestedFrames) {
                        break;
                    }
                }
                if (windowFrames == 0) {
                    break;
                }
                double rms = Math.sqrt(squaredAmplitude / windowFrames);
                double dbfs = Math.max(-100, 20 * Math.log10(Math.max(rms, SILENCE_FLOOR)));
                long windowStartFrame = totalFrames - windowFrames;
                long timestamp = Math.round((windowStartFrame + windowFrames / 2.0)
                        * 1000.0 / format.getFrameRate());
                WindowFeatures features = calculateFeatures(monoSamples, Math.toIntExact(windowFrames),
                        format.getFrameRate());
                windows.add(new AudioEnergyWindow(timestamp, Math.min(0, dbfs), features.peakDbfs(),
                        features.zeroCrossingRate(), features.medianPitchHz(), features.pitchVarianceHz(),
                        features.voicedFrameRatio()));
                if (windowFrames < framesPerWindow) {
                    break;
                }
            }
            long durationMs = Math.round(totalFrames * 1000.0 / format.getFrameRate());
            return new AudioEnergyAnalysis(durationMs, windows);
        } catch (UnsupportedAudioFileException | IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to analyze the extracted PCM WAV audio", exception);
        }
    }

    private static WindowFeatures calculateFeatures(float[] samples, int sampleCount, double sampleRate) {
        int peakWindowSamples = Math.max(1, (int) Math.round(sampleRate / 10.0));
        double peakSquaredAmplitude = 0;
        int peakSamples = 0;
        double peakDbfs = -100;
        long zeroCrossings = 0;
        for (int index = 0; index < sampleCount; index++) {
            double sample = samples[index];
            peakSquaredAmplitude += sample * sample;
            peakSamples++;
            if (index > 0 && (samples[index - 1] < 0) != (sample < 0)) {
                zeroCrossings++;
            }
            if (peakSamples == peakWindowSamples || index == sampleCount - 1) {
                double rms = Math.sqrt(peakSquaredAmplitude / peakSamples);
                peakDbfs = Math.max(peakDbfs, 20 * Math.log10(Math.max(rms, SILENCE_FLOOR)));
                peakSquaredAmplitude = 0;
                peakSamples = 0;
            }
        }

        int pitchFrameSamples = Math.max(1, (int) Math.round(sampleRate * 0.04));
        int pitchStride = Math.max(1, (int) Math.round(sampleRate / 8_000.0));
        int pitchStep = Math.max(pitchFrameSamples, (int) Math.round(sampleRate * 0.30));
        List<Double> pitches = new ArrayList<>();
        int pitchFrameCount = 0;
        int firstPitchSample = Math.min((int) (sampleRate * 0.08),
                Math.max(0, sampleCount - pitchFrameSamples));
        for (int start = firstPitchSample; start + pitchFrameSamples <= sampleCount; start += pitchStep) {
            pitchFrameCount++;
            double pitch = estimatePitch(samples, start, pitchFrameSamples, sampleRate, pitchStride);
            if (pitch > 0) {
                pitches.add(pitch);
            }
        }
        double medianPitch = 0;
        double pitchVariance = 0;
        if (!pitches.isEmpty()) {
            pitches.sort(Double::compareTo);
            int middle = pitches.size() / 2;
            medianPitch = pitches.size() % 2 == 0
                    ? (pitches.get(middle - 1) + pitches.get(middle)) / 2.0
                    : pitches.get(middle);
            double mean = pitches.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            pitchVariance = Math.sqrt(pitches.stream()
                    .mapToDouble(pitch -> (pitch - mean) * (pitch - mean)).average().orElse(0));
        }
        double zeroCrossingRate = sampleCount < 2 ? 0 : (double) zeroCrossings / (sampleCount - 1);
        double voicedRatio = pitchFrameCount == 0 ? 0 : (double) pitches.size() / pitchFrameCount;
        return new WindowFeatures(Math.min(0, peakDbfs), zeroCrossingRate, medianPitch,
                pitchVariance, voicedRatio);
    }

    private static double estimatePitch(float[] samples, int start, int frameLength,
                                        double sampleRate, int stride) {
        int effectiveRate = (int) (sampleRate / stride);
        int sampleCount = frameLength / stride;
        int minimumLag = Math.max(2, (int) Math.ceil(effectiveRate / 400.0));
        int maximumLag = Math.min(sampleCount / 2, (int) Math.floor(effectiveRate / 70.0));
        if (maximumLag <= minimumLag) {
            return 0;
        }

        double mean = 0;
        for (int index = 0, sampleIndex = start; index < sampleCount;
             index++, sampleIndex += stride) {
            mean += samples[sampleIndex];
        }
        mean /= sampleCount;
        double[] correlations = new double[maximumLag + 1];
        for (int lag = minimumLag; lag <= maximumLag; lag++) {
            double cross = 0;
            double leftEnergy = 0;
            double rightEnergy = 0;
            int comparedSamples = sampleCount - lag;
            for (int index = 0; index < comparedSamples; index++) {
                double left = samples[start + index * stride] - mean;
                double right = samples[start + (index + lag) * stride] - mean;
                cross += left * right;
                leftEnergy += left * left;
                rightEnergy += right * right;
            }
            correlations[lag] = leftEnergy == 0 || rightEnergy == 0
                    ? 0 : cross / Math.sqrt(leftEnergy * rightEnergy);
        }
        for (int lag = minimumLag + 1; lag < maximumLag; lag++) {
            double correlation = correlations[lag];
            if (correlation >= 0.65 && correlation >= correlations[lag - 1]
                    && correlation >= correlations[lag + 1]) {
                return effectiveRate / (double) lag;
            }
        }
        return 0;
    }

    private static int readFrames(AudioInputStream audio, byte[] buffer, int requestedBytes, int frameSize)
            throws IOException {
        int bytesRead = audio.read(buffer, 0, requestedBytes);
        if (bytesRead == 0) {
            throw new IOException("WAV stream did not return audio data");
        }
        if (bytesRead > 0 && bytesRead % frameSize != 0) {
            throw new IOException("WAV stream ended in the middle of an audio frame");
        }
        return bytesRead;
    }

    private static void validateFormat(AudioFormat format) {
        AudioFormat.Encoding encoding = format.getEncoding();
        int sampleBits = format.getSampleSizeInBits();
        int channels = format.getChannels();
        if (channels < 1 || sampleBits < 8 || format.getFrameSize() <= 0
                || (encoding.equals(AudioFormat.Encoding.PCM_FLOAT)
                ? sampleBits != 32 && sampleBits != 64
                : (sampleBits > 32 || sampleBits % 8 != 0
                || !(encoding.equals(AudioFormat.Encoding.PCM_SIGNED)
                || encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED))))) {
            throw new IllegalArgumentException("WAV must contain supported PCM audio");
        }
        if (format.getFrameSize() < channels * (sampleBits / 8)
                || !Float.isFinite(format.getFrameRate()) || format.getFrameRate() <= 0) {
            throw new IllegalArgumentException("WAV audio format has invalid frame metadata");
        }
    }

    private static double normalizedSample(byte[] bytes, int offset, AudioFormat format) {
        int sampleBits = format.getSampleSizeInBits();
        int byteCount = sampleBits / 8;
        long raw = 0;
        if (format.isBigEndian()) {
            for (int index = 0; index < byteCount; index++) {
                raw = (raw << 8) | (bytes[offset + index] & 0xffL);
            }
        } else {
            for (int index = byteCount - 1; index >= 0; index--) {
                raw = (raw << 8) | (bytes[offset + index] & 0xffL);
            }
        }
        if (format.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT)) {
            double value = sampleBits == 32 ? Float.intBitsToFloat((int) raw) : Double.longBitsToDouble(raw);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("WAV contains a non-finite floating-point sample");
            }
            return Math.max(-1, Math.min(1, value));
        }
        long midpoint = 1L << (sampleBits - 1);
        long value = format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)
                ? raw - midpoint
                : (raw & midpoint) == 0 ? raw : raw - (1L << sampleBits);
        return Math.max(-1, Math.min(1, value / (double) midpoint));
    }

    private record WindowFeatures(double peakDbfs, double zeroCrossingRate,
                                  double medianPitchHz, double pitchVarianceHz,
                                  double voicedFrameRatio) {
    }
}
