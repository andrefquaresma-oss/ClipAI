package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateDetectionTrigger;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpringCandidateDetectionTrigger implements CandidateDetectionTrigger {
    private final AsyncCandidateDetectionWorker worker;

    public SpringCandidateDetectionTrigger(AsyncCandidateDetectionWorker worker) {
        this.worker = worker;
    }

    @Override
    public void schedule(UUID mediaAssetId) {
        worker.process(mediaAssetId);
    }
}
