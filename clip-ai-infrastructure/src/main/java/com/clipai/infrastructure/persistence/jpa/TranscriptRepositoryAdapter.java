package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.transcript.Transcript;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TranscriptRepositoryAdapter implements TranscriptRepository {
    private final TranscriptJpaRepository repository;
    private final MediaAssetJpaRepository mediaAssets;

    public TranscriptRepositoryAdapter(TranscriptJpaRepository repository, MediaAssetJpaRepository mediaAssets) {
        this.repository = repository;
        this.mediaAssets = mediaAssets;
    }

    @Override
    @Transactional
    public Transcript save(Transcript transcript) {
        MediaAssetJpaEntity mediaAsset = mediaAssets.getReferenceById(transcript.getMediaAssetId());
        return repository.save(TranscriptJpaEntity.fromDomain(transcript, mediaAsset)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transcript> findByMediaAssetId(UUID mediaAssetId) {
        return repository.findByMediaAsset_Id(mediaAssetId).map(TranscriptJpaEntity::toDomain);
    }
}
