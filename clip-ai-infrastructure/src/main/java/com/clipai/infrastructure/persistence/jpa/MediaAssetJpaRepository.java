package com.clipai.infrastructure.persistence.jpa;

import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.media.MediaAssetStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface MediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, UUID> {
    org.springframework.data.domain.Page<MediaAssetJpaEntity> findByStatus(
            MediaAssetStatus status, org.springframework.data.domain.Pageable pageable);

    @Query("""
            select asset from MediaAssetJpaEntity asset
            where (:status is null or asset.status = :status)
              and (:term = '' or
                   lower(asset.title) like concat('%', :term, '%') or
                   lower(coalesce(asset.originalFilename, '')) like concat('%', :term, '%') or
                   lower(coalesce(asset.competition, '')) like concat('%', :term, '%') or
                   lower(coalesce(asset.homeTeam, '')) like concat('%', :term, '%') or
                   lower(coalesce(asset.awayTeam, '')) like concat('%', :term, '%') or
                   lower(asset.source) like concat('%', :term, '%'))
            """)
    Page<MediaAssetJpaEntity> search(@Param("status") MediaAssetStatus status,
                                     @Param("term") String term, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaAssetJpaEntity asset
               set asset.candidateDetectionStatus = :processing,
                   asset.candidateDetectionFailureReason = null,
                   asset.updatedAt = :now
             where asset.id = :id
               and asset.status = :completed
               and asset.candidateDetectionStatus <> :processing
            """)
    int claimCandidateDetection(@Param("id") UUID id,
                                @Param("now") Instant now,
                                @Param("completed") MediaAssetStatus completed,
                                @Param("processing") CandidateDetectionStatus processing);
}
