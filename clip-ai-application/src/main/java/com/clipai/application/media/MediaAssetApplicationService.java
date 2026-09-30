package com.clipai.application.media;

import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;

import java.time.Clock;
import java.util.UUID;

public final class MediaAssetApplicationService {
    private final MediaAssetRepository repository;
    private final Clock clock;

    public MediaAssetApplicationService(MediaAssetRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public MediaAsset register(RegisterMediaAssetCommand command) {
        MediaAsset asset = MediaAsset.register(command.source(), command.sourceUrl(), command.title(),
                command.contentType(), clock.instant());
        return repository.save(asset);
    }

    public MediaAssetPage list(int page, int size, MediaAssetStatus status) {
        return list(page, size, status, null, "createdAt", true);
    }

    public MediaAssetPage list(int page, int size, MediaAssetStatus status,
                               String search, String sort, boolean descending) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        if (!java.util.Set.of("createdAt", "updatedAt", "title", "durationMs", "matchDate")
                .contains(sort)) {
            throw new IllegalArgumentException("sort must be createdAt, updatedAt, title, durationMs, or matchDate");
        }
        return repository.findAll(page, size, status, search, sort, descending);
    }

    public MediaAsset get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new MediaAssetNotFoundException(id));
    }
}
