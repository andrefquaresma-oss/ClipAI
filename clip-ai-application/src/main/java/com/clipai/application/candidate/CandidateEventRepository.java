package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateEventRepository {
    List<CandidateEvent> findByMediaAssetId(UUID mediaAssetId);

    Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaAssetId);

    default List<CandidateEvent> findByDetectionRunId(UUID detectionRunId) {
        return List.of();
    }

    default List<CandidateEvent> findLegacyByMediaAssetId(UUID mediaAssetId) {
        return findByMediaAssetId(mediaAssetId).stream()
                .filter(event -> event.detectionRunId() == null).toList();
    }

    default void appendForRun(UUID detectionRunId, UUID mediaAssetId, List<CandidateEvent> events) {
        throw new UnsupportedOperationException("Run-scoped candidate persistence is not supported");
    }

    default void appendLegacyForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) {
        throw new UnsupportedOperationException("Legacy candidate persistence is not supported");
    }

    @Deprecated
    default void replaceForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) {
        appendLegacyForMediaAsset(mediaAssetId, events);
    }

    default CandidateEvent saveManual(CandidateEvent event) {
        throw new UnsupportedOperationException("Manual candidate creation is not supported");
    }
}
