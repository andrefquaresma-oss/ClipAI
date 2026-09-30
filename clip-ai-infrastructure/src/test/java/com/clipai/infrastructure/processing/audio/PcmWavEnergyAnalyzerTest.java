package com.clipai.infrastructure.processing.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmWavEnergyAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void computesTimestampedRmsWindowsFromPcmWav() throws Exception {
        int sampleRate = 16_000;
        byte[] pcm = new byte[sampleRate * 2 * 2];
        for (int sampleIndex = 0; sampleIndex < sampleRate * 2; sampleIndex++) {
            short amplitude = sampleIndex < sampleRate ? (short) 1_000 : (short) 16_000;
            pcm[sampleIndex * 2] = (byte) (amplitude & 0xff);
            pcm[sampleIndex * 2 + 1] = (byte) ((amplitude >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        Path wav = temporaryDirectory.resolve("energy.wav");
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm),
                format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav.toFile());
        }

        var result = new PcmWavEnergyAnalyzer().analyze(wav, 1_000);

        assertEquals(2_000, result.durationMs());
        assertEquals(2, result.windows().size());
        assertEquals(500, result.windows().getFirst().timestampMs());
        assertEquals(1_500, result.windows().getLast().timestampMs());
        assertTrue(result.windows().getLast().dbfs() > result.windows().getFirst().dbfs() + 20);
    }

    @Test
    void estimatesPitchAndVoicedFrameRatioFromPeriodicSpeechLikeAudio() throws Exception {
        int sampleRate = 16_000;
        int frequency = 150;
        byte[] pcm = new byte[sampleRate * 2];
        for (int sampleIndex = 0; sampleIndex < sampleRate; sampleIndex++) {
            short amplitude = (short) (12_000 * Math.sin(2 * Math.PI * frequency * sampleIndex / sampleRate));
            pcm[sampleIndex * 2] = (byte) (amplitude & 0xff);
            pcm[sampleIndex * 2 + 1] = (byte) ((amplitude >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        Path wav = temporaryDirectory.resolve("pitch.wav");
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm),
                format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav.toFile());
        }

        var result = new PcmWavEnergyAnalyzer().analyze(wav, 1_000);

        assertEquals(1, result.windows().size());
        assertTrue(Math.abs(result.windows().getFirst().medianPitchHz() - frequency) < 10);
        assertTrue(result.windows().getFirst().voicedFrameRatio() >= 0.5);
    }
}
