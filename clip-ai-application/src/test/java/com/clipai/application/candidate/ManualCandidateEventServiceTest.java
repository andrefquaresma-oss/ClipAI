package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManualCandidateEventServiceTest {
    @Test
    void createsManualCandidateWithoutChangingMediaDetectionState() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        MediaAsset asset = MediaAsset.restore(UUID.randomUUID(), "LOCAL_UPLOAD", null, null, "match",
                ContentType.SPORTS, 180_000L, "media/source.mp4", MediaAssetStatus.COMPLETED, now, now);
        AtomicReference<CandidateEvent> stored = new AtomicReference<>();
        CandidateEventRepository candidates = new CandidateEventRepository() {
            @Override public List<CandidateEvent> findByMediaAssetId(UUID id) { return List.of(); }
            @Override public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID assetId) {
                return Optional.empty();
            }
            @Override public void replaceForMediaAsset(UUID id, List<CandidateEvent> events) { }
            @Override public CandidateEvent saveManual(CandidateEvent event) {
                stored.set(event); return event;
            }
        };
        MediaAssetRepository assets = assets(asset);
        ManualCandidateEventService service = new ManualCandidateEventService(assets, candidates,
                Clock.fixed(now, ZoneOffset.UTC), 60_000);

        CandidateEvent created = service.create(asset.getId(), FootballEventType.GOAL,
                30_000, 10_000, 50_000, "Reviewed by operator");

        assertEquals(created.id(), stored.get().id());
        assertEquals(com.clipai.domain.candidate.CandidateEventStatus.MANUAL, created.status());
        assertEquals(30_000, created.triggerTimestampMs());
        assertEquals("Reviewed by operator", created.transcriptContext());
    }

    @Test
    void rejectsManualWindowsOutsideBounds() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        MediaAsset asset = MediaAsset.restore(UUID.randomUUID(), "LOCAL_UPLOAD", null, null, "match",
                ContentType.SPORTS, 60_000L, "media/source.mp4", MediaAssetStatus.COMPLETED, now, now);
        ManualCandidateEventService service = new ManualCandidateEventService(assets(asset), emptyCandidates(),
                Clock.fixed(now, ZoneOffset.UTC), 60_000);
        assertThrows(IllegalArgumentException.class, () -> service.create(asset.getId(),
                FootballEventType.GOAL, 61_000, 10_000, 62_000, null));
    }

    private static CandidateEventRepository emptyCandidates() {
        return new CandidateEventRepository() {
            @Override public List<CandidateEvent> findByMediaAssetId(UUID id) { return List.of(); }
            @Override public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID assetId) {
                return Optional.empty();
            }
            @Override public void replaceForMediaAsset(UUID id, List<CandidateEvent> events) { }
        };
    }

    private static MediaAssetRepository assets(MediaAsset asset) {
        return new MediaAssetRepository() {
            @Override public MediaAsset save(MediaAsset value) { return value; }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return id.equals(asset.getId()) ? Optional.of(asset) : Optional.empty();
            }
            @Override public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                return new MediaAssetPage(List.of(asset), page, size, 1);
            }
        };
    }
}
