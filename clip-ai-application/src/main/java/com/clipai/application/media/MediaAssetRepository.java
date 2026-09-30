package com.clipai.application.media;

import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository {
    MediaAsset save(MediaAsset mediaAsset);

    Optional<MediaAsset> findById(UUID id);

    MediaAssetPage findAll(int page, int size, MediaAssetStatus status);

    default MediaAssetPage findAll(int page, int size, MediaAssetStatus status,
                                   String search, String sort, boolean descending) {
        return findAll(page, size, status);
    }
}
