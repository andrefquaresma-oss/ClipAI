package com.clipai.application.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingStageRepository {
    List<ProcessingStageRun> findByMediaAssetId(UUID mediaAssetId);

    ProcessingStageRun save(ProcessingStageRun run);

    static ProcessingStageRepository none() {
        return new ProcessingStageRepository() {
            @Override
            public List<ProcessingStageRun> findByMediaAssetId(UUID mediaAssetId) {
                return List.of();
            }

            @Override
            public ProcessingStageRun save(ProcessingStageRun run) {
                return run;
            }
        };
    }
}
