package com.clipai.infrastructure.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegVideoClipperTest {
    @TempDir
    Path directory;

    @Test
    void cutsAccurateMillisecondWindowWithoutInvokingAShell() throws Exception {
        Path source = Files.writeString(directory.resolve("source video.mp4"), "video");
        Path output = directory.resolve("clips").resolve("goal.mp4");
        List<String> executedArguments = new ArrayList<>();
        ExternalProcessExecutor executor = (arguments, timeout) -> {
            executedArguments.addAll(arguments);
            try {
                Files.writeString(Path.of(arguments.getLast()), "encoded clip");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new ProcessResult(0, "", "", 10);
        };
        FfmpegVideoClipper clipper = new FfmpegVideoClipper(executor, "ffmpeg", Duration.ofMinutes(2));

        clipper.cut(source, output, 1_234, 4_000);

        assertEquals(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-ss", "1.234",
                "-i", source.toAbsolutePath().toString(), "-t", "2.766",
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "20", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                "-avoid_negative_ts", "make_zero", output.toAbsolutePath().toString()), executedArguments);
        assertEquals("encoded clip", Files.readString(output));
        assertTrue(Files.isDirectory(output.getParent()));
    }

    @Test
    void extractsSingleTimestampedJpegFrameWithoutInvokingAShell() throws Exception {
        Path source = Files.writeString(directory.resolve("source.mp4"), "video");
        Path output = directory.resolve("frames").resolve("frame-01.jpg");
        List<String> executedArguments = new ArrayList<>();
        ExternalProcessExecutor executor = (arguments, timeout) -> {
            executedArguments.addAll(arguments);
            try {
                Files.writeString(Path.of(arguments.getLast()), "jpeg frame");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new ProcessResult(0, "", "", 10);
        };
        FfmpegVideoClipper clipper = new FfmpegVideoClipper(executor, "ffmpeg", Duration.ofMinutes(2));

        clipper.extractFrame(source, output, 12_345);

        assertEquals(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-ss", "12.345",
                "-i", source.toAbsolutePath().toString(), "-map", "0:v:0", "-frames:v", "1",
                "-q:v", "2", output.toAbsolutePath().toString()), executedArguments);
        assertEquals("jpeg frame", Files.readString(output));
        assertTrue(Files.isDirectory(output.getParent()));
    }
}
