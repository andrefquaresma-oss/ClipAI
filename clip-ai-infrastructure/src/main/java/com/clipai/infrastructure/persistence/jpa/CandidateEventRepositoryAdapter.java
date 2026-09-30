package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.domain.candidate.CandidateEvent;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CandidateEventRepositoryAdapter implements CandidateEventRepository {
    private final CandidateEventJpaRepository repository;
    private final MediaAssetJpaRepository mediaAssets;

    public CandidateEventRepositoryAdapter(CandidateEventJpaRepository repository,
                                           MediaAssetJpaRepository mediaAssets) {
        this.repository = repository;
        this.mediaAssets = mediaAssets;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateEvent> findByMediaAssetId(UUID mediaAssetId) {
        return repository.findByMediaAsset_IdOrderByScoreDescStartTimeMsAscIdAsc(mediaAssetId)
                .stream().map(CandidateEventJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaAssetId) {
        return repository.findByIdAndMediaAsset_Id(id, mediaAssetId).map(CandidateEventJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateEvent> findByDetectionRunId(UUID detectionRunId) {
        return repository.findByDetectionRunIdOrderByScoreDescStartTimeMsAscIdAsc(detectionRunId)
                .stream().map(CandidateEventJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateEvent> findLegacyByMediaAssetId(UUID mediaAssetId) {
        return repository.findByMediaAsset_IdAndDetectionRunIdIsNullOrderByScoreDescStartTimeMsAscIdAsc(mediaAssetId)
                .stream().map(CandidateEventJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void appendForRun(UUID detectionRunId, UUID mediaAssetId, List<CandidateEvent> events) {
        persist(mediaAssetId, events.stream().map(event -> event.withDetectionRunId(detectionRunId)).toList());
    }

    @Override
    @Transactional
    public void appendLegacyForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) {
        persist(mediaAssetId, events.stream().map(event -> event.withDetectionRunId(null)).toList());
    }

    private void persist(UUID mediaAssetId, List<CandidateEvent> events) {
        MediaAssetJpaEntity mediaAsset = mediaAssets.getReferenceById(mediaAssetId);
        repository.saveAll(events.stream().map(event -> CandidateEventJpaEntity.fromDomain(event, mediaAsset))
                .toList());
    }

    @Override
    @Transactional
    public CandidateEvent saveManual(CandidateEvent event) {
        MediaAssetJpaEntity mediaAsset = mediaAssets.getReferenceById(event.mediaAssetId());
        return repository.save(CandidateEventJpaEntity.fromDomain(event, mediaAsset)).toDomain();
    }
}
