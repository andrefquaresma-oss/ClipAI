package com.clipai.application.transcript;

import com.clipai.domain.transcript.Transcript;

import java.util.Optional;
import java.util.UUID;

public interface TranscriptRepository {
    Transcript save(Transcript transcript);

    Optional<Transcript> findByMediaAssetId(UUID mediaAssetId);
}
