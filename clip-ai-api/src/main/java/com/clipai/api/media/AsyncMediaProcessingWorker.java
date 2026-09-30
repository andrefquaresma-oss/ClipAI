package com.clipai.api.media;

import com.clipai.application.media.MediaProcessingService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AsyncMediaProcessingWorker {
    private final MediaProcessingService processingService;

    public AsyncMediaProcessingWorker(MediaProcessingService processingService) {
        this.processingService = processingService;
    }

    @Async("mediaProcessingExecutor")
    public void process(UUID mediaAssetId) {
        processingService.process(mediaAssetId);
    }

    @Async("mediaProcessingExecutor")
    public void extractAudio(UUID mediaAssetId) {
        processingService.extractAudio(mediaAssetId);
    }

    @Async("mediaProcessingExecutor")
    public void transcribe(UUID mediaAssetId) {
        processingService.transcribe(mediaAssetId);
    }

    @Async("mediaProcessingExecutor")
    public void retry(UUID mediaAssetId) {
        processingService.retry(mediaAssetId);
    }
}
