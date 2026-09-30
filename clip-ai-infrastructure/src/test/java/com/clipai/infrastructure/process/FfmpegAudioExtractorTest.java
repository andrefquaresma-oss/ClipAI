package com.clipai.infrastructure.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FfmpegAudioExtractorTest {
    @TempDir
    Path directory;

    @Test
    void reportsFfmpegFailureWithoutInvokingShellOrLosingArguments() throws Exception {
        Path source = Files.writeString(directory.resolve("source with spaces.mp4"), "video");
        FakeExecutor executor = new FakeExecutor(new ProcessResult(1, "", "no audio stream", 10));
        FfmpegAudioExtractor extractor = new FfmpegAudioExtractor(executor, "C:\\tools\\ffmpeg.exe",
                Duration.ofSeconds(5));

        MediaProcessingException failure = assertThrows(MediaProcessingException.class,
                () -> extractor.extractAudio(source, directory.resolve("audio.wav")));

        assertEquals("Audio extraction failed", failure.publicReason());
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("no audio stream"));
        assertEquals("C:\\tools\\ffmpeg.exe", executor.arguments.getFirst());
        assertEquals(source.toAbsolutePath().toString(), executor.arguments.get(5));
    }

    private static final class FakeExecutor implements ExternalProcessExecutor {
        private final ProcessResult result;
        private List<String> arguments;

        private FakeExecutor(ProcessResult result) {
            this.result = result;
        }

        @Override
        public ProcessResult execute(List<String> arguments, Duration timeout) {
            this.arguments = List.copyOf(arguments);
            return result;
        }
    }
}
