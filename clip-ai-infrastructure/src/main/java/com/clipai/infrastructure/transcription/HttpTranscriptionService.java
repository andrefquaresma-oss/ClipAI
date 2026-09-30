package com.clipai.infrastructure.transcription;

import com.clipai.application.ports.TranscriptionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class HttpTranscriptionService implements TranscriptionService {
    private final RestClient restClient;

    public HttpTranscriptionService(@Qualifier("transcriptionWorkerRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public TranscriptionResult transcribe(Path audioPath, String language) {
        try {
            WorkerResponse response = restClient.post()
                    .uri("/transcriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WorkerRequest(audioPath.toAbsolutePath().toString(), workerLanguage(language)))
                    .retrieve()
                    .body(WorkerResponse.class);
            if (response == null || response.segments() == null) {
                throw new TranscriptionException("Transcription service returned an invalid response",
                        "Whisper worker returned an empty response");
            }
            List<TranscribedSegment> segments = response.segments().stream()
                    .map(segment -> new TranscribedSegment(segment.startMs(), segment.endMs(), segment.text()))
                    .toList();
            return new TranscriptionResult(response.language(), segments);
        } catch (RestClientException exception) {
            throw new TranscriptionException("Transcription service failed",
                    "Whisper worker request failed: " + exception.getMessage(), exception);
        }
    }

    private static String workerLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().replace('_', '-');
        String primaryTag = normalized.split("-", 2)[0].toLowerCase(Locale.ROOT);
        if (primaryTag.matches("[a-z]{2,3}")) {
            return primaryTag;
        }
        return Arrays.stream(Locale.getISOLanguages())
                .filter(code -> Locale.forLanguageTag(code)
                        .getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(normalized.toLowerCase(Locale.ROOT));
    }

    private record WorkerRequest(String audioPath, String language) {
    }

    private record WorkerResponse(String language, List<WorkerSegment> segments) {
    }

    private record WorkerSegment(long startMs, long endMs, String text) {
    }
}
