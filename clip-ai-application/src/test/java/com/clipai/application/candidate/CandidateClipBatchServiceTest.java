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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateClipBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generatesOnlyDetectedSupportedCandidatesWithExactWindowsAndReusesExistingClips(
            @TempDir Path root) throws IOException {
        UUID mediaAssetId = UUID.randomUUID();
        Path source = Files.writeString(root.resolve("source.mp4"), "source");
        CandidateEvent goal = detected(mediaAssetId, FootballEventType.GOAL, 65_000, 125_000, 90_000);
        CandidateEvent rejected = CandidateEvent.rejected(mediaAssetId, 10_000, 50_000, 30_000,
                FootballEventType.GOAL, 0.3, List.of(new CandidateSignal(CandidateSignalType.REJECTION_REASON,
                        FootballEventType.GOAL, 0.9, 30_000, "LOW_LIVE_EVENT_PROBABILITY")),
                "rejected goal hypothesis", NOW);
        CandidateEvent unsupported = detected(mediaAssetId, FootballEventType.FOUL, 200_000, 230_000, 210_000);
        Fixture fixture = fixture(root, source, mediaAssetId, List.of(goal, rejected, unsupported), false);

        CandidateClipBatch started = fixture.batches().start(mediaAssetId);
        assertEquals(CandidateClipBatchStatus.PROCESSING, started.status());
        assertEquals(2, started.totalCandidates());
        assertEquals(CandidateClipGenerationStatus.PENDING,
                started.clips().get(1).generationStatus());

        fixture.batches().generate(mediaAssetId, started.batchId());

        CandidateClipBatch completed = fixture.batches().getLatest(mediaAssetId);
        CandidateClipBatchItem generated = completed.clips().getFirst();
        assertEquals(CandidateClipBatchStatus.COMPLETED, completed.status());
        assertEquals(CandidateClipGenerationStatus.GENERATED, generated.generationStatus());
        assertEquals(goal.id(), generated.candidateId());
        assertEquals(65_000, generated.startTimeMs());
        assertEquals(125_000, generated.endTimeMs());
        assertEquals(60_000, generated.durationMs());
        assertNotNull(generated.storageKey());
        Path output = root.resolve(generated.storageKey());
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.size(output) > 0);
        assertTrue(completed.clips().get(1).storageKey().contains("/other/"));
        assertEquals(CandidateClipGenerationStatus.GENERATED,
                completed.clips().get(1).generationStatus());
        assertEquals(2, fixture.cutWindows().size());
        assertArrayEquals(new long[] {65_000, 125_000}, fixture.cutWindows().getFirst());
        assertArrayEquals(new long[] {200_000, 230_000}, fixture.cutWindows().get(1));

        CandidateClipBatch retry = fixture.batches().start(mediaAssetId);
        fixture.batches().generate(mediaAssetId, retry.batchId());

        CandidateClipBatchItem reused = fixture.batches().getLatest(mediaAssetId).clips().getFirst();
        assertEquals(CandidateClipGenerationStatus.REUSED, reused.generationStatus());
        assertEquals(CandidateClipGenerationStatus.REUSED,
                fixture.batches().getLatest(mediaAssetId).clips().get(1).generationStatus());
        assertEquals(2, fixture.cutWindows().size());
    }

    @Test
    void reportsClipGenerationFailureAndContinuesBatch(@TempDir Path root) throws IOException {
        UUID mediaAssetId = UUID.randomUUID();
        Path source = Files.writeString(root.resolve("source.mp4"), "source");
        CandidateEvent goal = detected(mediaAssetId, FootballEventType.GOAL, 65_000, 125_000, 90_000);
        Fixture fixture = fixture(root, source, mediaAssetId, List.of(goal), true);

        CandidateClipBatch started = fixture.batches().start(mediaAssetId);
        fixture.batches().generate(mediaAssetId, started.batchId());

        CandidateClipBatch result = fixture.batches().getLatest(mediaAssetId);
        assertEquals(CandidateClipBatchStatus.COMPLETED_WITH_FAILURES, result.status());
        assertEquals(CandidateClipGenerationStatus.FAILED, result.clips().getFirst().generationStatus());
        assertEquals("Clip generation failed", result.clips().getFirst().failureReason());
    }

    @Test
    void generatesOnlyOneClipForFourDuplicateCanonicalizedCards(@TempDir Path root) throws IOException {
        UUID mediaAssetId = UUID.randomUUID();
        Path source = Files.writeString(root.resolve("source.mp4"), "source");
        List<CandidateEvent> duplicateCards = List.of(
                detectedDuplicateCard(mediaAssetId, 80_000, 110_000, 90_000),
                detectedDuplicateCard(mediaAssetId, 81_000, 111_000, 91_000),
                detectedDuplicateCard(mediaAssetId, 82_000, 112_000, 92_000),
                detectedDuplicateCard(mediaAssetId, 83_000, 113_000, 93_000));
        List<CandidateEvent> canonical = new CandidateEventClusterer(
                CandidateEventClusteringSettings.defaults()).cluster(duplicateCards);
        Fixture fixture = fixture(root, source, mediaAssetId, canonical, false);

        CandidateClipBatch started = fixture.batches().start(mediaAssetId);
        assertEquals(1, started.totalCandidates());
        assertEquals(4, started.clips().getFirst().sourceCandidateIds().size());

        fixture.batches().generate(mediaAssetId, started.batchId());

        assertEquals(1, fixture.cutWindows().size());
        CandidateClipBatchItem generated = fixture.batches().getLatest(mediaAssetId).clips().getFirst();
        assertEquals(CandidateClipGenerationStatus.GENERATED, generated.generationStatus());
        assertEquals(4, generated.sourceCandidateIds().size());
        assertTrue(generated.mergeReason().contains("Merged 4 YELLOW_CARD detections"));
    }

    private static CandidateEvent detectedDuplicateCard(UUID mediaAssetId, long startMs,
                                                        long endMs, long triggerMs) {
        FootballEventType eventType = FootballEventType.YELLOW_CARD;
        return CandidateEvent.detected(mediaAssetId, startMs, endMs, triggerMs, eventType, 0.92,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        eventType, 0.92, triggerMs, "Football phrase match: yellow card"),
                        new CandidateSignal(CandidateSignalType.TRANSCRIPT_CARD,
                                eventType, 0.92, triggerMs, "Card incident")),
                "Foul on the same player, referee shows a yellow card.", NOW);
    }

    private static CandidateEvent detected(UUID mediaAssetId, FootballEventType eventType,
                                           long startMs, long endMs, long triggerMs) {
        return CandidateEvent.detected(mediaAssetId, startMs, endMs, triggerMs, eventType, 0.92,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        eventType, 0.92, triggerMs, "test event")), "test candidate", NOW);
    }

    private static Fixture fixture(Path root, Path source, UUID mediaAssetId,
                                   List<CandidateEvent> events, boolean fail) {
        MediaAsset asset = MediaAsset.restore(mediaAssetId, "LOCAL_UPLOAD", null, null, "match.mp4",
                ContentType.SPORTS, null, "media/" + mediaAssetId + "/source.mp4",
                MediaAssetStatus.COMPLETED, NOW, NOW);
        MediaAssetRepository mediaAssets = new MediaAssetRepository() {
            @Override
            public MediaAsset save(MediaAsset mediaAsset) {
                return mediaAsset;
            }

            @Override
            public Optional<MediaAsset> findById(UUID id) {
                return id.equals(mediaAssetId) ? Optional.of(asset) : Optional.empty();
            }

            @Override
            public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                return new MediaAssetPage(List.of(), page, size, 0);
            }
        };
        CandidateEventRepository candidates = new CandidateEventRepository() {
            @Override
            public List<CandidateEvent> findByMediaAssetId(UUID id) {
                return id.equals(mediaAssetId) ? events : List.of();
            }

            @Override
            public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID assetId) {
                return events.stream().filter(event -> event.id().equals(id)
                        && event.mediaAssetId().equals(assetId)).findFirst();
            }

            @Override
            public void replaceForMediaAsset(UUID id, List<CandidateEvent> replacements) {
            }
        };
        MediaStorage mediaStorage = new MediaStorage() {
            @Override
            public String store(UUID id, String extension, java.io.InputStream content) {
                return "media/" + id + "/source.mp4";
            }

            @Override
            public Path resolve(String storageKey) {
                return source;
            }

            @Override
            public Path audioPath(String sourceStorageKey) {
                return source;
            }

            @Override
            public void delete(String storageKey) {
            }
        };
        ClipStorage clipStorage = new ClipStorage() {
            @Override
            public ClipStorageLocation prepare(UUID id, CandidateClipCategory category, UUID candidateId) {
                Path path = root.resolve("media").resolve(id.toString()).resolve("clips")
                        .resolve(category.folderName()).resolve(candidateId + ".mp4");
                return new ClipStorageLocation("media/" + id + "/clips/" + category.folderName()
                        + "/" + candidateId + ".mp4", path);
            }

            @Override
            public Optional<ClipStorageLocation> find(UUID id, CandidateClipCategory category, UUID candidateId) {
                ClipStorageLocation location = prepare(id, category, candidateId);
                return Files.isRegularFile(location.path()) ? Optional.of(location) : Optional.empty();
            }

            @Override
            public void delete(ClipStorageLocation location) {
                try {
                    Files.deleteIfExists(location.path());
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        List<long[]> cutWindows = new ArrayList<>();
        VideoClipper videoClipper = new VideoClipper() {
            @Override
            public void cut(Path sourcePath, Path outputPath, long startTimeMs, long endTimeMs) {
                cutWindows.add(new long[] {startTimeMs, endTimeMs});
                if (fail) {
                    throw new IllegalStateException("simulated FFmpeg failure");
                }
                try {
                    Files.createDirectories(outputPath.getParent());
                    Files.write(outputPath, new byte[] {0, 0, 0, 24, 102, 116, 121, 112});
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public void extractFrame(Path sourcePath, Path outputPath, long timestampMs) {
            }
        };
        CandidateClipService clipService = new CandidateClipService(mediaAssets, candidates, mediaStorage,
                clipStorage, videoClipper);
        CandidateClipBatchService batchService = new CandidateClipBatchService(mediaAssets, candidates, clipService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(batchService, cutWindows);
    }

    private record Fixture(CandidateClipBatchService batches, List<long[]> cutWindows) {
    }
}
