package com.clipai.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateEventJpaRepository extends JpaRepository<CandidateEventJpaEntity, UUID> {
    List<CandidateEventJpaEntity> findByMediaAsset_IdOrderByScoreDescStartTimeMsAscIdAsc(UUID mediaAssetId);

    Optional<CandidateEventJpaEntity> findByIdAndMediaAsset_Id(UUID id, UUID mediaAssetId);

    List<CandidateEventJpaEntity> findByDetectionRunIdOrderByScoreDescStartTimeMsAscIdAsc(UUID detectionRunId);

    List<CandidateEventJpaEntity> findByMediaAsset_IdAndDetectionRunIdIsNullOrderByScoreDescStartTimeMsAscIdAsc(
            UUID mediaAssetId);
}
