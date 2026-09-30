package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MediaAssetRepositoryAdapter implements MediaAssetRepository {
    private final MediaAssetJpaRepository repository;

    public MediaAssetRepositoryAdapter(MediaAssetJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public MediaAsset save(MediaAsset mediaAsset) {
        return repository.save(MediaAssetJpaEntity.fromDomain(mediaAsset)).toDomain();
    }

    @Override
    public Optional<MediaAsset> findById(UUID id) {
        return repository.findById(id).map(MediaAssetJpaEntity::toDomain);
    }

    @Override
    public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
        return findAll(page, size, status, null, "createdAt", true);
    }

    @Override
    public MediaAssetPage findAll(int page, int size, MediaAssetStatus status,
                                  String search, String sort, boolean descending) {
        Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest request = PageRequest.of(page, size, Sort.by(direction, sort));
        String term = search == null || search.isBlank() ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
        Page<MediaAssetJpaEntity> results = repository.search(status, term, request);
        return new MediaAssetPage(results.getContent().stream().map(MediaAssetJpaEntity::toDomain).toList(),
                page, size, results.getTotalElements());
    }
}
