package com.clipai.infrastructure.process;

import com.clipai.application.ports.AudioExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
public class FfmpegAudioExtractor implements AudioExtractor {
    private static final int MAX_ERROR_CHARS = 2000;
    private final ExternalProcessExecutor processExecutor;
    private final String ffmpegPath;
    private final Duration timeout;

    public FfmpegAudioExtractor(ExternalProcessExecutor processExecutor,
                                @Value("${ffmpeg.path:ffmpeg}") String ffmpegPath,
                                @Value("${ffmpeg.timeout:PT30M}") Duration timeout) {
        this.processExecutor = processExecutor;
        this.ffmpegPath = ffmpegPath;
        this.timeout = timeout;
    }

    @Override
    public Path extractAudio(Path mediaPath, Path audioPath) {
        if (mediaPath == null || !Files.isRegularFile(mediaPath) || !Files.isReadable(mediaPath)) {
            throw new MediaProcessingException("Source video is missing or unreadable",
                    "Media path does not point to a readable regular file");
        }
        if (audioPath == null || audioPath.getParent() == null) {
            throw new MediaProcessingException("Audio extraction failed", "Audio output path is invalid");
        }
        try {
            Files.createDirectories(audioPath.getParent());
        } catch (IOException exception) {
            throw new MediaProcessingException("Audio extraction failed",
                    "Unable to prepare audio output location", exception);
        }

        List<String> arguments = List.of(ffmpegPath, "-hide_banner", "-nostdin", "-y",
                "-i", mediaPath.toAbsolutePath().toString(), "-map", "0:a:0", "-vn",
                "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                audioPath.toAbsolutePath().toString());
        ProcessResult result;
        try {
            result = processExecutor.execute(arguments, timeout);
        } catch (ProcessExecutionException exception) {
            String reason = exception.getMessage() != null && exception.getMessage().contains("timeout")
                    ? "Audio extraction timed out" : "FFmpeg is unavailable or could not be started";
            throw new MediaProcessingException(reason, exception.getMessage(), exception);
        }
        if (result.exitCode() != 0) {
            throw new MediaProcessingException("Audio extraction failed",
                    "FFmpeg exited with code " + result.exitCode() + ": " + truncate(result.stderr()));
        }
        try {
            if (!Files.isRegularFile(audioPath) || Files.size(audioPath) <= 44) {
                throw new MediaProcessingException("Audio extraction failed",
                        "FFmpeg did not produce a valid WAV audio file");
            }
        } catch (IOException exception) {
            throw new MediaProcessingException("Audio extraction failed",
                    "Unable to verify extracted audio", exception);
        }
        return audioPath;
    }

    private static String truncate(String text) {
        String value = text == null || text.isBlank() ? "no FFmpeg error output" : text.trim();
        return value.length() > MAX_ERROR_CHARS ? value.substring(value.length() - MAX_ERROR_CHARS) : value;
    }
}
