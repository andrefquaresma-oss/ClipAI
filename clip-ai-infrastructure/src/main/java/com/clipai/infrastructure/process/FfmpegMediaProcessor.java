package com.clipai.infrastructure.process;

import com.clipai.application.ports.AudioExtractor;
import com.clipai.application.ports.MediaProcessor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class FfmpegMediaProcessor implements MediaProcessor {
    private final AudioExtractor audioExtractor;

    public FfmpegMediaProcessor(AudioExtractor audioExtractor) {
        this.audioExtractor = audioExtractor;
    }

    @Override
    public ProcessingResult process(Path mediaPath, Path audioPath) {
        return new ProcessingResult(audioExtractor.extractAudio(mediaPath, audioPath));
    }
}
