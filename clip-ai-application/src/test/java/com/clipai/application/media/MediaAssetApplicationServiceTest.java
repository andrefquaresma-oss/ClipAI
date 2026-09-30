package com.clipai.application.media;

import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaAssetApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void registersUsingClockAndDelegatesListAndGet() {
        InMemoryRepository repository = new InMemoryRepository();
        MediaAssetApplicationService service = new MediaAssetApplicationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        MediaAsset created = service.register(new RegisterMediaAssetCommand("source",
                "https://example.com/watch/1", "Title", ContentType.PODCAST));

        assertEquals(MediaAssetStatus.PENDING, created.getStatus());
        assertEquals(NOW, created.getCreatedAt());
        assertEquals(created.getId(), service.get(created.getId()).getId());
        assertEquals(created.getId(), service.list(0, 20, null).items().getFirst().getId());
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 101, null));
    }

    private static final class InMemoryRepository implements MediaAssetRepository {
        private MediaAsset asset;

        @Override
        public MediaAsset save(MediaAsset mediaAsset) {
            asset = mediaAsset;
            return mediaAsset;
        }

        @Override
        public Optional<MediaAsset> findById(UUID id) {
            return asset != null && asset.getId().equals(id) ? Optional.of(asset) : Optional.empty();
        }

        @Override
        public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
            List<MediaAsset> result = asset == null || (status != null && status != asset.getStatus())
                    ? List.of() : List.of(asset);
            return new MediaAssetPage(result, page, size, result.size());
        }
    }
}
