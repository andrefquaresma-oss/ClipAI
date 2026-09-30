package com.clipai.api.media;

import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;

import java.util.UUID;

public record UploadMediaAssetResponse(UUID id, MediaAssetStatus status) {
    public static UploadMediaAssetResponse from(MediaAsset asset) {
        return new UploadMediaAssetResponse(asset.getId(), asset.getStatus());
    }
}
