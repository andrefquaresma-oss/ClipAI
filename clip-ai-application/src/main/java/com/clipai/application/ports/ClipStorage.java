package com.clipai.application.ports;

import com.clipai.application.candidate.CandidateClipCategory;

import java.util.Optional;
import java.util.UUID;

public interface ClipStorage {
    ClipStorageLocation prepare(UUID mediaAssetId, CandidateClipCategory category, UUID candidateId);

    Optional<ClipStorageLocation> find(UUID mediaAssetId, CandidateClipCategory category, UUID candidateId);

    void delete(ClipStorageLocation location);
}
