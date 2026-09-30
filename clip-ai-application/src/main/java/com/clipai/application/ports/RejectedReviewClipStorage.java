package com.clipai.application.ports;

import java.util.Optional;
import java.util.UUID;

public interface RejectedReviewClipStorage {
    ClipStorageLocation prepare(UUID mediaAssetId, UUID candidateId);

    Optional<ClipStorageLocation> find(UUID mediaAssetId, UUID candidateId);

    void delete(UUID mediaAssetId, UUID candidateId);
}
