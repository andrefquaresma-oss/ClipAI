package com.clipai.application.media;

import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.ports.MediaStorage;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;

public final class UploadMediaAssetService {
    private static final Logger log = LoggerFactory.getLogger(UploadMediaAssetService.class);

    private final MediaAssetRepository repository;
    private final MediaStorage storage;
    private final MediaProcessingTrigger processingTrigger;
    private final VideoUploadValidator validator;
    private final Clock clock;

    public UploadMediaAssetService(MediaAssetRepository repository, MediaStorage storage,
                                   MediaProcessingTrigger processingTrigger, long maxFileSizeBytes,
                                   Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.processingTrigger = processingTrigger;
        this.validator = new VideoUploadValidator(maxFileSizeBytes);
        this.clock = clock;
    }

    public MediaAsset upload(UploadMediaAssetCommand command) {
        return upload(command, true);
    }

    public MediaAsset upload(UploadMediaAssetCommand command, boolean processImmediately) {
        if (command == null || command.content() == null) {
            throw new InvalidVideoUploadException("Uploaded file is required");
        }
        String extension = validator.validateAndGetExtension(command.originalFilename(), command.fileSize());
        String safeFilename = validator.safeFilename(command.originalFilename());
        String source = command.source() == null || command.source().isBlank()
                ? "LOCAL_UPLOAD" : command.source().trim();
        String title = command.title() == null || command.title().isBlank()
                ? safeFilename : command.title().trim();
        ContentType contentType = command.contentType() == null ? ContentType.GENERIC : command.contentType();
        MediaAsset asset = MediaAsset.registerUpload(source, title, contentType, safeFilename,
                command.competition(), command.homeTeam(), command.awayTeam(),
                command.matchDate(), command.language(), command.matchPart(), clock.instant());

        String storageKey;
        try (var input = command.content().openStream()) {
            storageKey = storage.store(asset.getId(), extension, input);
        } catch (IOException exception) {
            throw new InvalidVideoUploadException("Unable to read uploaded file");
        }
        asset.markStored(storageKey, clock.instant());

        MediaAsset stored;
        try {
            stored = repository.save(asset);
        } catch (RuntimeException persistenceFailure) {
            try {
                storage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                persistenceFailure.addSuppressed(cleanupFailure);
            }
            throw persistenceFailure;
        }

        if (processImmediately) {
            try {
                processingTrigger.schedule(stored.getId());
            } catch (RuntimeException schedulingFailure) {
                stored.markFailed("Unable to schedule media processing", clock.instant());
                stored = repository.save(stored);
                log.error("Media processing could not be scheduled mediaAssetId={}", stored.getId(),
                        schedulingFailure);
            }
        }
        log.info("Media upload stored mediaAssetId={} status={}", stored.getId(), stored.getStatus());
        return stored;
    }
}
