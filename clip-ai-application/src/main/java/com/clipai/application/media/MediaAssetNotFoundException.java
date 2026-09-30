package com.clipai.application.media;

import java.util.UUID;

public final class MediaAssetNotFoundException extends RuntimeException {
    public MediaAssetNotFoundException(UUID id) {
        super("Media asset not found: " + id);
    }
}
