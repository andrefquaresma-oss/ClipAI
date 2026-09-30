package com.clipai.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TranscriptJpaRepository extends JpaRepository<TranscriptJpaEntity, UUID> {
    Optional<TranscriptJpaEntity> findByMediaAsset_Id(UUID mediaAssetId);
}
