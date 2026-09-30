package com.clipai.application.ports;

import java.util.UUID;

public interface MediaProcessingTrigger {
    void schedule(UUID mediaAssetId);

    default void scheduleRetry(UUID mediaAssetId) {
        schedule(mediaAssetId);
    }

    default void scheduleAudioExtraction(UUID mediaAssetId) {
        schedule(mediaAssetId);
    }

    default void scheduleTranscription(UUID mediaAssetId) {
        schedule(mediaAssetId);
    }
}
