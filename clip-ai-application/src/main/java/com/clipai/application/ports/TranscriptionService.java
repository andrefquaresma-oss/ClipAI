package com.clipai.application.ports;

import java.nio.file.Path;
import java.util.List;

public interface TranscriptionService {
    TranscriptionResult transcribe(Path audioPath, String language);

    record TranscriptionResult(String language, List<TranscribedSegment> segments) {
        public TranscriptionResult {
            segments = List.copyOf(segments);
        }
    }

    record TranscribedSegment(long startTimeMs, long endTimeMs, String text) {
    }
}
