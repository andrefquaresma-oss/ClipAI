package com.clipai.infrastructure.process;

import com.clipai.application.ports.VideoClipper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class FfmpegVideoClipper implements VideoClipper {
    private static final int MAX_ERROR_CHARS = 2000;

    private final ExternalProcessExecutor processExecutor;
    private final String ffmpegPath;
    private final Duration timeout;

    public FfmpegVideoClipper(ExternalProcessExecutor processExecutor,
                              @Value("${ffmpeg.path:ffmpeg}") String ffmpegPath,
                              @Value("${ffmpeg.clip-timeout:PT30M}") Duration timeout) {
        this.processExecutor = processExecutor;
        this.ffmpegPath = ffmpegPath;
        this.timeout = timeout;
    }

    @Override
    public void cut(Path sourcePath, Path outputPath, long startTimeMs, long endTimeMs) {
        if (sourcePath == null || !Files.isRegularFile(sourcePath) || !Files.isReadable(sourcePath)) {
            throw new MediaProcessingException("Clip export failed", "Source video is missing or unreadable");
        }
        if (outputPath == null || outputPath.getParent() == null || startTimeMs < 0
                || endTimeMs <= startTimeMs) {
            throw new MediaProcessingException("Clip export failed", "Clip output path or timestamps are invalid");
        }
        try {
            Files.createDirectories(outputPath.getParent());
        } catch (IOException exception) {
            throw new MediaProcessingException("Clip export failed",
                    "Unable to prepare clip output location", exception);
        }

        long durationMs = endTimeMs - startTimeMs;
        List<String> arguments = List.of(ffmpegPath, "-hide_banner", "-nostdin", "-y",
                "-ss", seconds(startTimeMs), "-i", sourcePath.toAbsolutePath().toString(),
                "-t", seconds(durationMs), "-map", "0:v:0", "-map", "0:a:0?",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                "-avoid_negative_ts", "make_zero", outputPath.toAbsolutePath().toString());
        ProcessResult result;
        try {
            result = processExecutor.execute(arguments, timeout);
        } catch (ProcessExecutionException exception) {
            String reason = exception.getMessage() != null && exception.getMessage().contains("timeout")
                    ? "Clip export timed out" : "FFmpeg is unavailable or could not be started";
            throw new MediaProcessingException(reason, exception.getMessage(), exception);
        }
        if (result.exitCode() != 0) {
            throw new MediaProcessingException("Clip export failed",
                    "FFmpeg exited with code " + result.exitCode() + ": " + truncate(result.stderr()));
        }
        try {
            if (!Files.isRegularFile(outputPath) || Files.size(outputPath) == 0) {
                throw new MediaProcessingException("Clip export failed",
                        "FFmpeg did not produce a valid video clip");
            }
        } catch (IOException exception) {
            throw new MediaProcessingException("Clip export failed",
                    "Unable to verify generated video clip", exception);
        }
    }

    @Override
    public void extractFrame(Path sourcePath, Path outputPath, long timestampMs) {
        if (sourcePath == null || !Files.isRegularFile(sourcePath) || !Files.isReadable(sourcePath)) {
            throw new MediaProcessingException("Frame extraction failed", "Source video is missing or unreadable");
        }
        if (outputPath == null || outputPath.getParent() == null || timestampMs < 0) {
            throw new MediaProcessingException("Frame extraction failed",
                    "Frame output path or timestamp is invalid");
        }
        try {
            Files.createDirectories(outputPath.getParent());
        } catch (IOException exception) {
            throw new MediaProcessingException("Frame extraction failed",
                    "Unable to prepare frame output location", exception);
        }

        List<String> arguments = List.of(ffmpegPath, "-hide_banner", "-nostdin", "-y",
                "-ss", seconds(timestampMs), "-i", sourcePath.toAbsolutePath().toString(),
                "-map", "0:v:0", "-frames:v", "1", "-q:v", "2",
                outputPath.toAbsolutePath().toString());
        ProcessResult result;
        try {
            result = processExecutor.execute(arguments, timeout);
        } catch (ProcessExecutionException exception) {
            String reason = exception.getMessage() != null && exception.getMessage().contains("timeout")
                    ? "Frame extraction timed out" : "FFmpeg is unavailable or could not be started";
            throw new MediaProcessingException(reason, exception.getMessage(), exception);
        }
        if (result.exitCode() != 0) {
            throw new MediaProcessingException("Frame extraction failed",
                    "FFmpeg exited with code " + result.exitCode() + ": " + truncate(result.stderr()));
        }
        try {
            if (!Files.isRegularFile(outputPath) || Files.size(outputPath) == 0) {
                throw new MediaProcessingException("Frame extraction failed",
                        "FFmpeg did not produce a valid image");
            }
        } catch (IOException exception) {
            throw new MediaProcessingException("Frame extraction failed",
                    "Unable to verify generated frame", exception);
        }
    }

    private static String seconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0);
    }

    private static String truncate(String text) {
        String value = text == null || text.isBlank() ? "no FFmpeg error output" : text.trim();
        return value.length() > MAX_ERROR_CHARS ? value.substring(value.length() - MAX_ERROR_CHARS) : value;
    }
}
