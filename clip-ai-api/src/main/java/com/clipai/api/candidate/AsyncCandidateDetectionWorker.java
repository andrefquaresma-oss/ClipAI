package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateDetectionProcessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AsyncCandidateDetectionWorker {
    private final CandidateDetectionProcessor processor;

    public AsyncCandidateDetectionWorker(CandidateDetectionProcessor processor) {
        this.processor = processor;
    }

    @Async("mediaProcessingExecutor")
    public void process(UUID mediaAssetId) {
        processor.process(mediaAssetId);
    }
}
