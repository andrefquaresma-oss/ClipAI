package com.clipai.application.ports;

import java.util.Optional;
import java.util.UUID;

public interface GoalReviewAssetStorage {
    ClipStorageLocation prepareFrame(UUID mediaAssetId, UUID candidateId, int frameNumber);

    Optional<ClipStorageLocation> findFrame(UUID mediaAssetId, UUID candidateId, int frameNumber);

    ClipStorageLocation prepareReplay(UUID mediaAssetId, UUID candidateId, int replayNumber);

    Optional<ClipStorageLocation> findReplay(UUID mediaAssetId, UUID candidateId, int replayNumber);
}
