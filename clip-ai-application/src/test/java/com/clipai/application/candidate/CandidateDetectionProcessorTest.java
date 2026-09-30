package com.clipai.application.candidate;

import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyAnalysis;
import com.clipai.application.candidate.AudioEnergyAnalyzer.AudioEnergyWindow;
import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.DetectionRun;
import com.clipai.domain.candidate.DetectionRunStatus;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import com.clipai.domain.transcript.TranscriptStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateDetectionProcessorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void persistsCandidatesAndCompletesDetectionState() {
        Fixture fixture = new Fixture((wav, windowMs) -> {
            List<AudioEnergyWindow> windows = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                windows.add(new AudioEnergyWindow(500L + index * 1_000L,
                        index == 4 || index == 5 ? -10 : -30));
            }
            return new AudioEnergyAnalysis(12_000, windows);
        });

        fixture.processor.process(fixture.run.id());

        assertEquals(CandidateDetectionStatus.COMPLETED, fixture.asset.getCandidateDetectionStatus());
        assertEquals(1, fixture.candidateEvents.events.size());
        CandidateEvent candidate = fixture.candidateEvents.events.getFirst();
        assertEquals(FootballEventType.GOAL, candidate.eventType());
        assertTrue(candidate.signals().size() >= 2);
        assertEquals("GOL!!!", candidate.transcriptContext());
        assertTrue(fixture.candidateObservations.signals.stream()
                .anyMatch(signal -> signal.type() == com.clipai.domain.candidate.CandidateSignalType.TRANSCRIPT_GOAL));
    }

    @Test
    void recordsFailedStatusWhenAudioAnalysisFails() {
        Fixture fixture = new Fixture((wav, windowMs) -> {
            throw new IllegalStateException("unsupported audio");
        });

        fixture.processor.process(fixture.run.id());

        assertEquals(CandidateDetectionStatus.FAILED, fixture.asset.getCandidateDetectionStatus());
        assertEquals("Candidate detection failed", fixture.asset.getCandidateDetectionFailureReason());
        assertTrue(fixture.candidateEvents.events.isEmpty());
    }

    private static final class Fixture {
        private final MediaAsset asset;
        private final Transcript transcript;
        private final InMemoryCandidateEvents candidateEvents = new InMemoryCandidateEvents();
        private final InMemoryCandidateObservations candidateObservations = new InMemoryCandidateObservations();
        private final InMemoryDetectionRuns detectionRuns = new InMemoryDetectionRuns();
        private final DetectionRun run;
        private final CandidateDetectionProcessor processor;

        private Fixture(AudioEnergyAnalyzer audioEnergyAnalyzer) {
            asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "match.mp4", ContentType.SPORTS, NOW);
            asset.markStored("media/" + asset.getId() + "/source.mp4", NOW.plusSeconds(1));
            asset.startProcessing(NOW.plusSeconds(2));
            asset.markCompleted(NOW.plusSeconds(3));
            asset.startCandidateDetection(NOW.plusSeconds(4));
            transcript = Transcript.create(asset.getId(), "pt", NOW);
            transcript.startProcessing(NOW.plusSeconds(1));
            transcript.addSegment(TranscriptSegment.create(transcript.getId(), 0,
                    4_500, 5_500, "GOL!!!"));
            transcript.markCompleted("pt", NOW.plusSeconds(2));

            CandidateDetectionSettings settings = settings();
            run = DetectionRun.pending(asset.getId(), NOW.plusSeconds(5), "test", "test-config");
            detectionRuns.asset = asset;
            detectionRuns.createPending(run);
            DetectionRunResultWriter resultWriter = (runId, mediaAssetId, observations, candidates, completedAt) -> {
                candidateObservations.signals = List.copyOf(observations);
                candidateEvents.events = List.copyOf(candidates);
                asset.completeCandidateDetection(completedAt);
                detectionRuns.complete(runId, completedAt, candidates.size(), observations.size());
            };
            processor = new CandidateDetectionProcessor(new InMemoryMediaAssets(asset),
                    new InMemoryTranscripts(transcript), detectionRuns, resultWriter,
                    new TestStorage(), audioEnergyAnalyzer, new TranscriptSignalDetector(settings),
                    new AudioExcitementDetector(settings), new CandidateEventAssembler(settings), settings,
                    Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        }

        private static final class InMemoryDetectionRuns implements DetectionRunRepository {
            private final Map<UUID, DetectionRun> runs = new HashMap<>();
            private MediaAsset asset;

            @Override
            public boolean createPending(DetectionRun run) {
                runs.put(run.id(), run);
                return true;
            }

            @Override
            public boolean markRunning(UUID runId, Instant startedAt) {
                DetectionRun run = runs.get(runId);
                if (run == null || run.status() != DetectionRunStatus.PENDING) {
                    return false;
                }
                runs.put(runId, run.start(startedAt));
                return true;
            }

            @Override
            public Optional<DetectionRun> findById(UUID runId) {
                return Optional.ofNullable(runs.get(runId));
            }

            @Override
            public List<DetectionRun> findByMediaAssetId(UUID mediaAssetId) {
                return runs.values().stream().filter(run -> run.mediaAssetId().equals(mediaAssetId)).toList();
            }

            @Override
            public void markFailed(UUID runId, Instant completedAt, String failureReason) {
                runs.computeIfPresent(runId, (id, run) -> run.fail(completedAt, failureReason));
                if (asset != null && asset.getCandidateDetectionStatus() == CandidateDetectionStatus.PROCESSING) {
                    asset.failCandidateDetection("Candidate detection failed", completedAt);
                }
            }

            private void complete(UUID runId, Instant completedAt, int candidates, int observations) {
                runs.computeIfPresent(runId, (id, run) -> run.complete(completedAt,
                        candidates, observations, candidates, 0));
            }
        }
    }

    private static CandidateDetectionSettings settings() {
        Map<FootballEventType, List<String>> portuguese = Map.of(FootballEventType.GOAL, List.of("gol"));
        Map<FootballEventType, Double> confidence = new EnumMap<>(FootballEventType.class);
        confidence.put(FootballEventType.GOAL, 0.88);
        return new CandidateDetectionSettings(1_000, 10_000, 6, -45, 1_500,
                12_000, 20_000, 5_000, 0.50, 0.70, 10,
                20_000, 2, 8, 0.65, 0.70, 0.30, 0.10,
                Map.of("pt", portuguese), confidence);
    }

    private static final class InMemoryMediaAssets implements MediaAssetRepository {
        private final MediaAsset asset;

        private InMemoryMediaAssets(MediaAsset asset) {
            this.asset = asset;
        }

        @Override
        public MediaAsset save(MediaAsset mediaAsset) {
            return mediaAsset;
        }

        @Override
        public Optional<MediaAsset> findById(UUID id) {
            return asset.getId().equals(id) ? Optional.of(asset) : Optional.empty();
        }

        @Override
        public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
            return new MediaAssetPage(List.of(asset), page, size, 1);
        }
    }

    private static final class InMemoryTranscripts implements TranscriptRepository {
        private final Transcript transcript;

        private InMemoryTranscripts(Transcript transcript) {
            this.transcript = transcript;
        }

        @Override
        public Transcript save(Transcript transcript) {
            return transcript;
        }

        @Override
        public Optional<Transcript> findByMediaAssetId(UUID mediaAssetId) {
            return transcript.getMediaAssetId().equals(mediaAssetId) ? Optional.of(transcript) : Optional.empty();
        }
    }

    private static final class InMemoryCandidateEvents implements CandidateEventRepository {
        private List<CandidateEvent> events = List.of();

        @Override
        public List<CandidateEvent> findByMediaAssetId(UUID mediaAssetId) {
            return events;
        }

        @Override
        public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaAssetId) {
            return events.stream().filter(event -> event.id().equals(id)
                    && event.mediaAssetId().equals(mediaAssetId)).findFirst();
        }

        @Override
        public void replaceForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) {
            this.events = List.copyOf(events);
        }
    }

    private static final class InMemoryCandidateObservations implements CandidateObservationRepository {
        private List<com.clipai.domain.candidate.CandidateSignal> signals = List.of();

        @Override
        public void replaceForMediaAsset(UUID mediaAssetId,
                                         List<com.clipai.domain.candidate.CandidateSignal> values) {
            signals = List.copyOf(values);
        }

        @Override
        public List<com.clipai.domain.candidate.CandidateObservation> findByMediaAssetIdAndTimestampRange(
                UUID mediaAssetId, long startTimeMs, long endTimeMs, int limit) {
            return List.of();
        }
    }

    private static final class TestStorage implements MediaStorage {
        @Override
        public String store(UUID mediaAssetId, String extension, java.io.InputStream content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path resolve(String storageKey) {
            return Path.of("match.mp4");
        }

        @Override
        public Path audioPath(String sourceStorageKey) {
            return Path.of("match.wav");
        }

        @Override
        public void delete(String storageKey) {
            throw new UnsupportedOperationException();
        }
    }
}
