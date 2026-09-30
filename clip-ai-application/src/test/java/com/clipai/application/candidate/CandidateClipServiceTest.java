package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorage;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateClipServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void goalClipUsesTheCandidateWindowWithoutAddingAnotherPreRoll() {
        CandidateEvent event = event(FootballEventType.GOAL, 65_000, 125_000, 90_000);
        AtomicReference<long[]> cutWindow = new AtomicReference<>();
        CandidateClipService service = service(event, cutWindow);

        CandidateClip clip = service.export(event.mediaAssetId(), event.id());

        assertEquals(65_000, clip.startTimeMs());
        assertEquals(125_000, clip.endTimeMs());
        assertArrayEquals(new long[] {65_000, 125_000}, cutWindow.get());
    }

    @Test
    void nonGoalClipKeepsDetectedWindow() {
        CandidateEvent event = event(FootballEventType.SHOT, 28_000, 60_000, 40_000);
        AtomicReference<long[]> cutWindow = new AtomicReference<>();
        CandidateClipService service = service(event, cutWindow);

        CandidateClip clip = service.export(event.mediaAssetId(), event.id());

        assertEquals(28_000, clip.startTimeMs());
        assertEquals(60_000, clip.endTimeMs());
        assertArrayEquals(new long[] {28_000, 60_000}, cutWindow.get());
    }

    @Test
    void goalClipPreservesCalculatedPreRollBoundary() {
        CandidateEvent event = event(FootballEventType.GOAL, 8_000, 50_000, 20_000);
        AtomicReference<long[]> cutWindow = new AtomicReference<>();
        CandidateClipService service = service(event, cutWindow);

        CandidateClip clip = service.export(event.mediaAssetId(), event.id());

        assertEquals(8_000, clip.startTimeMs());
        assertArrayEquals(new long[] {8_000, 50_000}, cutWindow.get());
    }

    @Test
    void goalClipIncludesEarlierDetectedBuildupBoundary() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent event = CandidateEvent.detected(mediaAssetId, 8_000, 60_000, 40_000,
                FootballEventType.GOAL, 0.9,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                                FootballEventType.GOAL, 0.9, 40_000, "goal"),
                        new CandidateSignal(CandidateSignalType.EVENT_BOUNDARY,
                                FootballEventType.GOAL, 0.8, 8_000,
                                "Start boundary follows the earliest detected attack-buildup cue")),
                "goal after a buildup", NOW);
        AtomicReference<long[]> cutWindow = new AtomicReference<>();
        CandidateClipService service = service(event, cutWindow);

        CandidateClip clip = service.export(event.mediaAssetId(), event.id());

        assertEquals(8_000, clip.startTimeMs());
        assertArrayEquals(new long[] {8_000, 60_000}, cutWindow.get());
    }

    @Test
    void exportsTheSavedManualBoundaryInsteadOfChangingAutomaticCandidateData() {
        CandidateEvent event = event(FootballEventType.GOAL, 65_000, 125_000, 90_000);
        AtomicReference<long[]> cutWindow = new AtomicReference<>();
        CandidateReview review = new CandidateReview(event.id(), CandidateReviewStatus.CONFIRMED,
                72_000L, 118_000L, NOW);
        CandidateClipService service = service(event, cutWindow, new CandidateReviewRepository() {
            @Override
            public Optional<CandidateReview> findByCandidateId(UUID candidateId) {
                return Optional.of(review);
            }

            @Override
            public CandidateReview save(CandidateReview candidateReview) {
                return candidateReview;
            }
        });

        CandidateClip clip = service.export(event.mediaAssetId(), event.id());

        assertEquals(65_000, event.startTimeMs());
        assertEquals(125_000, event.endTimeMs());
        assertEquals(72_000, clip.startTimeMs());
        assertEquals(118_000, clip.endTimeMs());
        assertArrayEquals(new long[] {72_000, 118_000}, cutWindow.get());
    }

    @Test
    void rejectedCandidateCannotBeExported() {
        UUID mediaAssetId = UUID.randomUUID();
        CandidateEvent event = CandidateEvent.rejected(mediaAssetId, 10_000, 60_000, 40_000,
                FootballEventType.GOAL, 0.7,
                List.of(new CandidateSignal(CandidateSignalType.REJECTION_REASON,
                        FootballEventType.GOAL, 0.8, 40_000, "LOW_LIVE_EVENT_PROBABILITY")),
                "replay discussion", NOW);
        CandidateClipService service = service(event, new AtomicReference<>());

        assertThrows(CandidateClipExportConflictException.class,
                () -> service.export(event.mediaAssetId(), event.id()));
    }

    private static CandidateEvent event(FootballEventType type, long startMs, long endMs, long triggerMs) {
        UUID mediaAssetId = UUID.randomUUID();
        return CandidateEvent.detected(mediaAssetId, startMs, endMs, triggerMs, type, 0.9,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        type, 0.9, triggerMs, "test signal")), "test context", NOW);
    }

    private static CandidateClipService service(CandidateEvent event, AtomicReference<long[]> cutWindow) {
        return service(event, cutWindow, CandidateReviewRepository.none());
    }

    private static CandidateClipService service(CandidateEvent event, AtomicReference<long[]> cutWindow,
                                                CandidateReviewRepository reviews) {
        MediaAsset asset = MediaAsset.restore(event.mediaAssetId(), "LOCAL_UPLOAD", null, null,
                "match.mp4", ContentType.SPORTS, null,
                "media/" + event.mediaAssetId() + "/source.mp4",
                MediaAssetStatus.COMPLETED, NOW, NOW);

        MediaAssetRepository mediaAssets = new MediaAssetRepository() {
            @Override
            public MediaAsset save(MediaAsset mediaAsset) {
                return mediaAsset;
            }

            @Override
            public Optional<MediaAsset> findById(UUID id) {
                return id.equals(asset.getId()) ? Optional.of(asset) : Optional.empty();
            }

            @Override
            public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                return new MediaAssetPage(List.of(), page, size, 0);
            }
        };
        CandidateEventRepository candidates = new CandidateEventRepository() {
            @Override
            public List<CandidateEvent> findByMediaAssetId(UUID mediaAssetId) {
                return mediaAssetId.equals(event.mediaAssetId()) ? List.of(event) : List.of();
            }

            @Override
            public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaAssetId) {
                return id.equals(event.id()) && mediaAssetId.equals(event.mediaAssetId())
                        ? Optional.of(event) : Optional.empty();
            }

            @Override
            public void replaceForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) {
            }
        };
        MediaStorage mediaStorage = new MediaStorage() {
            @Override
            public String store(UUID mediaAssetId, String extension, java.io.InputStream content) {
                return "media/" + mediaAssetId + "/source.mp4";
            }

            @Override
            public Path resolve(String storageKey) {
                return Path.of(storageKey);
            }

            @Override
            public Path audioPath(String sourceStorageKey) {
                return Path.of(sourceStorageKey);
            }

            @Override
            public void delete(String storageKey) {
            }
        };
        ClipStorage clipStorage = new ClipStorage() {
            @Override
            public ClipStorageLocation prepare(UUID mediaAssetId, CandidateClipCategory category,
                                               UUID candidateId) {
                return new ClipStorageLocation("clips/" + candidateId + ".mp4", Path.of("clip.mp4"));
            }

            @Override
            public Optional<ClipStorageLocation> find(UUID mediaAssetId, CandidateClipCategory category,
                                                      UUID candidateId) {
                return Optional.empty();
            }

            @Override
            public void delete(ClipStorageLocation location) {
            }
        };
        VideoClipper videoClipper = new VideoClipper() {
            @Override
            public void cut(Path sourcePath, Path outputPath, long startTimeMs, long endTimeMs) {
                cutWindow.set(new long[] {startTimeMs, endTimeMs});
            }

            @Override
            public void extractFrame(Path sourcePath, Path outputPath, long timestampMs) {
            }
        };
        return new CandidateClipService(mediaAssets, candidates, mediaStorage, clipStorage, videoClipper, reviews);
    }
}
