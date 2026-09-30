package com.clipai.api.media;

import com.clipai.application.ports.MediaProcessingTrigger;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpringMediaProcessingTrigger implements MediaProcessingTrigger {
    private final AsyncMediaProcessingWorker worker;

    public SpringMediaProcessingTrigger(AsyncMediaProcessingWorker worker) {
        this.worker = worker;
    }

    @Override
    public void schedule(UUID mediaAssetId) {
        worker.process(mediaAssetId);
    }

    @Override
    public void scheduleRetry(UUID mediaAssetId) {
        worker.retry(mediaAssetId);
    }

    @Override
    public void scheduleAudioExtraction(UUID mediaAssetId) {
        worker.extractAudio(mediaAssetId);
    }

    @Override
    public void scheduleTranscription(UUID mediaAssetId) {
        worker.transcribe(mediaAssetId);
    }
}
