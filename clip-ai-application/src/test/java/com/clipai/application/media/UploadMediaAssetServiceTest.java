package com.clipai.application.media;

import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.ports.MediaStorage;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.media.MediaAssetPart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UploadMediaAssetServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void storesUniqueUploadAndSchedulesProcessingAfterPersistence() {
        InMemoryRepository repository = new InMemoryRepository();
        RecordingStorage storage = new RecordingStorage();
        RecordingTrigger trigger = new RecordingTrigger();
        UploadMediaAssetService service = new UploadMediaAssetService(repository, storage, trigger, 100,
                Clock.fixed(NOW, ZoneOffset.UTC));

        MediaAsset asset = service.upload(new UploadMediaAssetCommand("../../capture.mp4", 11,
                null, null, null,
                () -> new ByteArrayInputStream("video-bytes".getBytes(StandardCharsets.UTF_8))));

        assertEquals("LOCAL_UPLOAD", asset.getSource());
        assertEquals("capture.mp4", asset.getTitle());
        assertEquals(ContentType.GENERIC, asset.getContentType());
        assertEquals(MediaAssetStatus.STORED, asset.getStatus());
        assertEquals("media/" + asset.getId() + "/source.mp4", asset.getLocalStoragePath());
        assertEquals(asset.getId(), trigger.scheduledId);
        assertEquals(asset.getId(), storage.storedId);
        assertNotNull(storage.content);
    }

    @Test
    void recordsSchedulingFailureInsteadOfPretendingProcessingWasQueued() {
        InMemoryRepository repository = new InMemoryRepository();
        UploadMediaAssetService service = new UploadMediaAssetService(repository, new RecordingStorage(),
                id -> { throw new IllegalStateException("executor unavailable"); }, 100,
                Clock.fixed(NOW, ZoneOffset.UTC));

        MediaAsset asset = service.upload(new UploadMediaAssetCommand("recording.webm", 4,
                "LOCAL_UPLOAD", "Title", ContentType.GENERIC,
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));

        assertEquals(MediaAssetStatus.FAILED, asset.getStatus());
        assertEquals("Unable to schedule media processing", asset.getFailureReason());
        assertEquals(asset.getId(), repository.saved.getId());
    }

    @Test
    void preservesTheSelectedMatchPartOnUploadedAssets() {
        InMemoryRepository repository = new InMemoryRepository();
        UploadMediaAssetService service = new UploadMediaAssetService(repository, new RecordingStorage(),
                id -> { }, 100, Clock.fixed(NOW, ZoneOffset.UTC));

        MediaAsset asset = service.upload(new UploadMediaAssetCommand("second-half.mp4", 4,
                "LOCAL_UPLOAD", "Barcelona vs Rayo · second half", ContentType.SPORTS,
                "La Liga", "Barcelona", "Rayo Vallecano", java.time.LocalDate.of(2024, 8, 24),
                "es", MediaAssetPart.SECOND_HALF, () -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4})),
                false);

        assertEquals(MediaAssetPart.SECOND_HALF, asset.getMatchPart());
        assertEquals("Barcelona", asset.getHomeTeam());
        assertEquals(MediaAssetStatus.STORED, asset.getStatus());
    }

    private static final class InMemoryRepository implements MediaAssetRepository {
        private MediaAsset saved;

        @Override
        public MediaAsset save(MediaAsset mediaAsset) {
            saved = mediaAsset;
            return mediaAsset;
        }

        @Override
        public Optional<MediaAsset> findById(UUID id) {
            return saved != null && saved.getId().equals(id) ? Optional.of(saved) : Optional.empty();
        }

        @Override
        public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
            List<MediaAsset> items = saved == null ? List.of() : List.of(saved);
            return new MediaAssetPage(items, page, size, items.size());
        }
    }

    private static final class RecordingStorage implements MediaStorage {
        private UUID storedId;
        private byte[] content;

        @Override
        public String store(UUID mediaAssetId, String extension, java.io.InputStream input) {
            storedId = mediaAssetId;
            try {
                content = input.readAllBytes();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
            return "media/" + mediaAssetId + "/source." + extension;
        }

        @Override
        public java.nio.file.Path resolve(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.file.Path audioPath(String sourceStorageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {
        }
    }

    private static final class RecordingTrigger implements MediaProcessingTrigger {
        private UUID scheduledId;

        @Override
        public void schedule(UUID mediaAssetId) {
            scheduledId = mediaAssetId;
        }
    }
}
