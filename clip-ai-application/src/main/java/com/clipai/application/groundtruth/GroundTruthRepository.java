package com.clipai.application.groundtruth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroundTruthRepository {
    Optional<GroundTruthMatchReview> findReview(UUID mediaAssetId);

    GroundTruthMatchReview saveReview(GroundTruthMatchReview review);

    List<GroundTruthEvent> findEvents(UUID mediaAssetId);

    GroundTruthEvent saveEvent(GroundTruthEvent event);

    void deleteEvent(UUID eventId, UUID mediaAssetId);
}
