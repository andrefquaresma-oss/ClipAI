package com.clipai.application.transcript;

import java.util.UUID;

public final class TranscriptNotFoundException extends RuntimeException {
    public TranscriptNotFoundException(UUID mediaAssetId) {
        super("Transcript not found for media asset: " + mediaAssetId);
    }
}
