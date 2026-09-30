package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateObservation;
import com.clipai.domain.candidate.CandidateSignal;

import java.util.List;
import java.util.UUID;

public interface CandidateObservationRepository {
    default void appendForRun(UUID detectionRunId, UUID mediaAssetId, List<CandidateSignal> signals) {
        throw new UnsupportedOperationException("Run-scoped observation persistence is not supported");
    }

    default void appendLegacyForMediaAsset(UUID mediaAssetId, List<CandidateSignal> signals) {
        throw new UnsupportedOperationException("Legacy observation persistence is not supported");
    }

    @Deprecated
    default void replaceForMediaAsset(UUID mediaAssetId, List<CandidateSignal> signals) {
        appendLegacyForMediaAsset(mediaAssetId, signals);
    }

    List<CandidateObservation> findByMediaAssetIdAndTimestampRange(
            UUID mediaAssetId, long startTimeMs, long endTimeMs, int limit);

    default List<CandidateObservation> findByMediaAssetIdAndRunIdAndTimestampRange(
            UUID mediaAssetId, UUID detectionRunId, long startTimeMs, long endTimeMs, int limit) {
        return findByMediaAssetIdAndTimestampRange(mediaAssetId, startTimeMs, endTimeMs, limit).stream()
                .filter(observation -> java.util.Objects.equals(detectionRunId, observation.detectionRunId()))
                .toList();
    }

    default long countByMediaAssetIdAndRunId(UUID mediaAssetId, UUID detectionRunId) {
        return 0;
    }
}
