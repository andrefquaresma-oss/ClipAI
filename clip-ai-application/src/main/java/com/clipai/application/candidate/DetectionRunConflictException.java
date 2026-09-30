package com.clipai.application.candidate;

import java.util.UUID;

public final class DetectionRunConflictException extends RuntimeException {
    public DetectionRunConflictException(UUID mediaAssetId, String message) {
        super("Detection run conflict for media asset " + mediaAssetId + ": " + message);
    }
}
