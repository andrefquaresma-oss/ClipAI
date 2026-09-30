package com.clipai.application.media;

import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.candidate.DetectionRunDescriptor;
import com.clipai.application.candidate.DetectionRunRepository;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.application.ports.MediaProcessor;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.TranscriptionService;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaProcessingServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path workDirectory;

    @Test
    void completesExtractionAndPersistsSegmentsInTimestampOrder() {
        Fixture fixture = new Fixture();
        MediaProcessingService service = fixture.service(new TranscriptionService.TranscriptionResult("en", List.of(
                new TranscriptionService.TranscribedSegment(2000, 3000, "Second"),
                new TranscriptionService.TranscribedSegment(100, 900, "First"))));

        service.process(fixture.asset.getId());

        assertEquals(MediaAssetStatus.COMPLETED,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getStatus());
        Transcript transcript = fixture.transcripts.findByMediaAssetId(fixture.asset.getId()).orElseThrow();
        assertEquals(TranscriptStatus.COMPLETED, transcript.getStatus());
        assertEquals("en", transcript.getLanguage());
        assertEquals(List.of("First", "Second"),
                transcript.getSegments().stream().map(segment -> segment.text()).toList());
        assertEquals(0, transcript.getSegments().getFirst().sequence());
        assertEquals(100, transcript.getSegments().getFirst().startTimeMs());
        assertEquals(CandidateDetectionStatus.PROCESSING,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getCandidateDetectionStatus());
    }

    @Test
    void exposesAudioExtractionAndTranscriptionAsIndependentStages() {
        Fixture fixture = new Fixture();
        MediaProcessingService service = fixture.service(
                new TranscriptionService.TranscriptionResult("en", List.of()));

        service.extractAudio(fixture.asset.getId());

        assertEquals(MediaAssetStatus.AUDIO_EXTRACTED,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getStatus());
        assertTrue(fixture.transcripts.findByMediaAssetId(fixture.asset.getId()).isEmpty());

        service.transcribe(fixture.asset.getId());

        assertEquals(MediaAssetStatus.COMPLETED,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getStatus());
        assertEquals(CandidateDetectionStatus.NOT_STARTED,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getCandidateDetectionStatus());
    }

    @Test
    void marksMediaFailedWhenFfmpegFails() {
        Fixture fixture = new Fixture();
        MediaProcessingService service = fixture.service(new IllegalStateException("FFmpeg exited with code 1"));
        fixture.failExtraction = true;

        service.process(fixture.asset.getId());

        MediaAsset failed = fixture.assets.findById(fixture.asset.getId()).orElseThrow();
        assertEquals(MediaAssetStatus.FAILED, failed.getStatus());
        assertEquals("FFmpeg exited with code 1", failed.getFailureReason());
        assertTrue(fixture.transcripts.findByMediaAssetId(fixture.asset.getId()).isEmpty());
    }

    @Test
    void marksBothTranscriptAndMediaFailedWhenWhisperFails() {
        Fixture fixture = new Fixture();
        MediaProcessingService service = fixture.service(new IllegalStateException("Whisper worker unavailable"));

        service.process(fixture.asset.getId());

        assertEquals(MediaAssetStatus.FAILED,
                fixture.assets.findById(fixture.asset.getId()).orElseThrow().getStatus());
        Transcript transcript = fixture.transcripts.findByMediaAssetId(fixture.asset.getId()).orElseThrow();
        assertEquals(TranscriptStatus.FAILED, transcript.getStatus());
        assertEquals("Whisper worker unavailable", transcript.getFailureReason());
    }

    private final class Fixture {
        private final InMemoryMediaAssets assets = new InMemoryMediaAssets();
        private final InMemoryTranscripts transcripts = new InMemoryTranscripts();
        private final MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "source.mp4",
                ContentType.GENERIC, NOW);
        private boolean failExtraction;

        private Fixture() {
            asset.markStored("media/" + asset.getId() + "/source.mp4", NOW.plusSeconds(1));
            assets.save(asset);
        }

        private MediaProcessingService service(TranscriptionService.TranscriptionResult result) {
            return service((audio, language) -> result);
        }

        private MediaProcessingService service(RuntimeException transcriptionFailure) {
            return service((audio, language) -> { throw transcriptionFailure; });
        }

        private MediaProcessingService service(TranscriptionService transcription) {
            MediaStorage storage = new MediaStorage() {
                @Override
                public String store(UUID mediaAssetId, String extension, java.io.InputStream content) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Path resolve(String storageKey) {
                    return workDirectory.resolve("source.mp4");
                }

                @Override
                public Path audioPath(String sourceStorageKey) {
                    return workDirectory.resolve("audio.wav");
                }

                @Override
                public void delete(String storageKey) {
                    throw new UnsupportedOperationException();
                }
            };
            MediaProcessor processor = (mediaPath, audioPath) -> {
                if (failExtraction) {
                    throw new IllegalStateException("FFmpeg exited with code 1");
                }
                return new MediaProcessor.ProcessingResult(audioPath);
            };
            Clock clock = Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);
            CandidateDetectionScheduler candidateDetectionScheduler =
                    new CandidateDetectionScheduler(assets, transcripts, new DetectionRunRepository() {
                        @Override
                        public boolean createPending(com.clipai.domain.candidate.DetectionRun run) {
                            return true;
                        }

                        @Override
                        public boolean markRunning(UUID runId, Instant startedAt) {
                            return true;
                        }

                        @Override
                        public Optional<com.clipai.domain.candidate.DetectionRun> findById(UUID runId) {
                            return Optional.empty();
                        }

                        @Override
                        public List<com.clipai.domain.candidate.DetectionRun> findByMediaAssetId(UUID mediaAssetId) {
                            return List.of();
                        }

                        @Override
                        public void markFailed(UUID runId, Instant completedAt, String failureReason) {
                        }
                    }, new DetectionRunDescriptor("test", "test-config"), ignored -> { }, clock);
            return new MediaProcessingService(assets, transcripts, storage, processor, transcription,
                    candidateDetectionScheduler, clock);
        }
    }

    private static final class InMemoryMediaAssets implements com.clipai.application.media.MediaAssetRepository {
        private final Map<UUID, MediaAsset> values = new HashMap<>();

        @Override
        public MediaAsset save(MediaAsset mediaAsset) {
            values.put(mediaAsset.getId(), mediaAsset);
            return mediaAsset;
        }

        @Override
        public Optional<MediaAsset> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
            return new MediaAssetPage(List.copyOf(values.values()), page, size, values.size());
        }
    }

    private static final class InMemoryTranscripts implements TranscriptRepository {
        private final Map<UUID, Transcript> values = new HashMap<>();

        @Override
        public Transcript save(Transcript transcript) {
            values.put(transcript.getMediaAssetId(), transcript);
            return transcript;
        }

        @Override
        public Optional<Transcript> findByMediaAssetId(UUID mediaAssetId) {
            return Optional.ofNullable(values.get(mediaAssetId));
        }
    }
}
