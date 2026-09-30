package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipBatchService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AsyncCandidateClipBatchWorker {
    private final CandidateClipBatchService batches;

    public AsyncCandidateClipBatchWorker(CandidateClipBatchService batches) {
        this.batches = batches;
    }

    @Async("mediaProcessingExecutor")
    public void generate(UUID mediaAssetId, UUID batchId) {
        batches.generate(mediaAssetId, batchId);
    }
}
