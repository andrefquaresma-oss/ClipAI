package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.RejectedReviewClipStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RejectedCandidateReviewClipServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void lazilyGeneratesAndReusesABoundedTemporaryClip() throws Exception {
        Fixture fixture = fixture();

        RejectedReviewClipResult requested = fixture.service().request(fixture.assetId(), fixture.candidate().id());

        assertEquals(RejectedReviewClipStatus.PROCESSING, requested.status());
        assertTrue(fixture.clipperCalls().isEmpty());
        fixture.queuedTasks().remove().run();

        RejectedReviewClipResult ready = fixture.service().get(fixture.assetId(), fixture.candidate().id());
        assertEquals(RejectedReviewClipStatus.READY, ready.status());
        assertEquals(15_000, ready.startTimeMs());
        assertEquals(45_000, ready.endTimeMs());
        assertEquals(30_000, ready.triggerTimestampMs());
        assertEquals(List.of(new ClipWindow(15_000, 45_000)), fixture.clipperCalls());
        assertTrue(fixture.service().find(fixture.assetId(), fixture.candidate().id()).isPresent());
        assertEquals(RejectedReviewClipStatus.READY,
                fixture.service().request(fixture.assetId(), fixture.candidate().id()).status());
        assertEquals(1, fixture.clipperCalls().size());
    }

    @Test
    void confirmationDeletesOnlyTheTemporaryReviewClipAndPreventsFurtherStreaming() throws Exception {
        Fixture fixture = fixture();
        fixture.service().request(fixture.assetId(), fixture.candidate().id());
        fixture.queuedTasks().remove().run();
        ClipStorageLocation location = fixture.service().find(fixture.assetId(), fixture.candidate().id())
                .orElseThrow();

        assertFalse(fixture.service().deleteAfterConfirmation(fixture.assetId(), fixture.candidate().id()));
        fixture.savedReview().set(new CandidateReview(fixture.candidate().id(), CandidateReviewStatus.REJECTED,
                null, null, "Not an event", null, HumanRejectionReason.NOT_A_FOOTBALL_EVENT, NOW));

        assertTrue(fixture.service().deleteAfterConfirmation(fixture.assetId(), fixture.candidate().id()));
        assertFalse(Files.exists(location.path()));
        assertTrue(fixture.service().find(fixture.assetId(), fixture.candidate().id()).isEmpty());
        assertEquals(RejectedReviewClipStatus.DELETED,
                fixture.service().get(fixture.assetId(), fixture.candidate().id()).status());
    }

    @Test
    void confirmationDuringGenerationDefersCleanupUntilTheWorkerFinishes() throws Exception {
        Fixture fixture = fixture();
        fixture.service().request(fixture.assetId(), fixture.candidate().id());
        fixture.savedReview().set(new CandidateReview(fixture.candidate().id(), CandidateReviewStatus.REJECTED,
                null, null, "Not an event", null, HumanRejectionReason.NOT_A_FOOTBALL_EVENT, NOW));

        assertTrue(fixture.service().deleteAfterConfirmation(fixture.assetId(), fixture.candidate().id()));
        assertEquals(RejectedReviewClipStatus.PROCESSING,
                fixture.service().get(fixture.assetId(), fixture.candidate().id()).status());
        fixture.queuedTasks().remove().run();

        assertEquals(RejectedReviewClipStatus.DELETED,
                fixture.service().get(fixture.assetId(), fixture.candidate().id()).status());
        assertTrue(fixture.service().find(fixture.assetId(), fixture.candidate().id()).isEmpty());
    }

    @Test
    void createsReviewClipWhenLegacyAssetHasNoStoredDuration() throws Exception {
        Fixture fixture = fixture(null);

        RejectedReviewClipResult requested = fixture.service().request(fixture.assetId(), fixture.candidate().id());
        fixture.queuedTasks().remove().run();

        assertEquals(RejectedReviewClipStatus.PROCESSING, requested.status());
        assertEquals(RejectedReviewClipStatus.READY,
                fixture.service().get(fixture.assetId(), fixture.candidate().id()).status());
        assertEquals(List.of(new ClipWindow(15_000, 45_000)), fixture.clipperCalls());
    }

    private Fixture fixture() throws Exception {
        return fixture(80_000L);
    }

    private Fixture fixture(Long durationMs) throws Exception {
        UUID assetId = UUID.randomUUID();
        CandidateEvent candidate = CandidateEvent.rejected(assetId, 10_000, 50_000, 30_000,
                FootballEventType.GOAL, 0.4,
                List.of(new CandidateSignal(CandidateSignalType.REJECTION_REASON,
                        FootballEventType.GOAL, 0.9, 30_000, "Insufficient evidence")),
                "goal-like event", NOW);
        MediaAsset asset = MediaAsset.restore(assetId, "LOCAL_UPLOAD", null, null, "match.mp4",
                ContentType.SPORTS, durationMs, "media/" + assetId + "/source.mp4",
                MediaAssetStatus.COMPLETED, NOW, NOW);
        MediaAssetRepository mediaAssets = new MediaAssetRepository() {
            @Override public MediaAsset save(MediaAsset value) { return value; }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return id.equals(assetId) ? Optional.of(asset) : Optional.empty();
            }
            @Override public com.clipai.application.media.MediaAssetPage findAll(
                    int page, int size, MediaAssetStatus status) {
                return new com.clipai.application.media.MediaAssetPage(List.of(asset), page, size, 1);
            }
        };
        CandidateEventRepository candidates = new CandidateEventRepository() {
            @Override public List<CandidateEvent> findByMediaAssetId(UUID id) {
                return id.equals(assetId) ? List.of(candidate) : List.of();
            }
            @Override public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaId) {
                return id.equals(candidate.id()) && mediaId.equals(assetId)
                        ? Optional.of(candidate) : Optional.empty();
            }
            @Override public void replaceForMediaAsset(UUID id, List<CandidateEvent> events) { }
        };
        AtomicReference<CandidateReview> savedReview = new AtomicReference<>();
        CandidateReviewRepository reviews = new CandidateReviewRepository() {
            @Override public Optional<CandidateReview> findByCandidateId(UUID id) {
                CandidateReview review = savedReview.get();
                return review != null && review.candidateId().equals(id)
                        ? Optional.of(review) : Optional.empty();
            }
            @Override public CandidateReview save(CandidateReview review) {
                savedReview.set(review);
                return review;
            }
        };
        MediaStorage mediaStorage = new MediaStorage() {
            @Override public String store(UUID id, String extension, java.io.InputStream content) {
                return "media/" + id + "/source." + extension;
            }
            @Override public Path resolve(String storageKey) { return root.resolve("source.mp4"); }
            @Override public Path audioPath(String sourceStorageKey) { return root.resolve("audio.wav"); }
            @Override public void delete(String storageKey) { }
        };
        Path output = root.resolve("review.mp4");
        MemoryReviewClipStorage clipStorage = new MemoryReviewClipStorage(output);
        List<ClipWindow> clipperCalls = new java.util.ArrayList<>();
        VideoClipper videoClipper = new VideoClipper() {
            @Override public void cut(Path sourcePath, Path outputPath, long startTimeMs, long endTimeMs) {
                clipperCalls.add(new ClipWindow(startTimeMs, endTimeMs));
                try {
                    Files.writeString(outputPath, "temporary clip");
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            @Override public void extractFrame(Path sourcePath, Path outputPath, long timestampMs) { }
        };
        ArrayDeque<Runnable> queuedTasks = new ArrayDeque<>();
        Executor executor = queuedTasks::add;
        return new Fixture(assetId, asset, candidate,
                new RejectedCandidateReviewClipService(mediaAssets, candidates, reviews,
                        mediaStorage, clipStorage, videoClipper, executor, 30_000),
                queuedTasks, clipperCalls, savedReview);
    }

    private record ClipWindow(long startTimeMs, long endTimeMs) {
    }

    private record Fixture(UUID assetId, MediaAsset asset, CandidateEvent candidate,
                           RejectedCandidateReviewClipService service, ArrayDeque<Runnable> queuedTasks,
                           List<ClipWindow> clipperCalls, AtomicReference<CandidateReview> savedReview) {
    }

    private static final class MemoryReviewClipStorage implements RejectedReviewClipStorage {
        private final Path path;

        private MemoryReviewClipStorage(Path path) {
            this.path = path;
        }

        @Override
        public ClipStorageLocation prepare(UUID mediaAssetId, UUID candidateId) {
            return new ClipStorageLocation("review-clips/rejected/" + candidateId + ".mp4", path);
        }

        @Override
        public Optional<ClipStorageLocation> find(UUID mediaAssetId, UUID candidateId) {
            return Files.isRegularFile(path)
                    ? Optional.of(prepare(mediaAssetId, candidateId)) : Optional.empty();
        }

        @Override
        public void delete(UUID mediaAssetId, UUID candidateId) {
            try {
                Files.deleteIfExists(path);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
