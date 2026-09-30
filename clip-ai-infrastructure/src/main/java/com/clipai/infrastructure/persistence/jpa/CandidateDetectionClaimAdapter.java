package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.candidate.CandidateDetectionClaim;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.media.MediaAssetStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class CandidateDetectionClaimAdapter implements CandidateDetectionClaim {
    private final MediaAssetJpaRepository repository;

    public CandidateDetectionClaimAdapter(MediaAssetJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean claim(UUID mediaAssetId, Instant now) {
        return repository.claimCandidateDetection(mediaAssetId, now,
                MediaAssetStatus.COMPLETED, CandidateDetectionStatus.PROCESSING) == 1;
    }
}
