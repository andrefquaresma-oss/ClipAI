package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.DetectionRun;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CandidateDetectionProcessor {
    private static final Logger log = LoggerFactory.getLogger(CandidateDetectionProcessor.class);

    private final MediaAssetRepository mediaAssets;
    private final TranscriptRepository transcripts;
    private final DetectionRunRepository detectionRuns;
    private final DetectionRunResultWriter resultWriter;
    private final MediaStorage storage;
    private final AudioEnergyAnalyzer audioEnergyAnalyzer;
    private final TranscriptSignalDetector transcriptDetector;
    private final AudioExcitementDetector audioDetector;
    private final CandidateContextSignalDetector contextDetector;
    private final CandidateGoalDiscovery goalDiscovery;
    private final CandidateEventAssembler assembler;
    private final CandidateEventClusterer eventClusterer;
    private final CandidateDetectionSettings settings;
    private final Clock clock;

    public CandidateDetectionProcessor(MediaAssetRepository mediaAssets, TranscriptRepository transcripts,
                                       DetectionRunRepository detectionRuns,
                                       DetectionRunResultWriter resultWriter,
                                       MediaStorage storage, AudioEnergyAnalyzer audioEnergyAnalyzer,
                                       TranscriptSignalDetector transcriptDetector,
                                       AudioExcitementDetector audioDetector,
                                       CandidateEventAssembler assembler,
                                       CandidateDetectionSettings settings, Clock clock) {
        this(mediaAssets, transcripts, detectionRuns, resultWriter, storage,
                audioEnergyAnalyzer, transcriptDetector, audioDetector, assembler, settings, clock,
                new CandidateContextSignalDetector(CandidateDetectionQualitySettings.defaults()),
                new CandidateGoalDiscovery(CandidateDetectionQualitySettings.defaults()),
                new CandidateEventClusterer(CandidateEventClusteringSettings.defaults()));
    }

    public CandidateDetectionProcessor(MediaAssetRepository mediaAssets, TranscriptRepository transcripts,
                                       DetectionRunRepository detectionRuns,
                                       DetectionRunResultWriter resultWriter,
                                       MediaStorage storage,
                                       AudioEnergyAnalyzer audioEnergyAnalyzer,
                                       TranscriptSignalDetector transcriptDetector,
                                       AudioExcitementDetector audioDetector,
                                       CandidateEventAssembler assembler,
                                       CandidateDetectionSettings settings, Clock clock,
                                       CandidateContextSignalDetector contextDetector,
                                       CandidateGoalDiscovery goalDiscovery,
                                       CandidateEventClusterer eventClusterer) {
        this.mediaAssets = mediaAssets;
        this.transcripts = transcripts;
        this.detectionRuns = detectionRuns;
        this.resultWriter = resultWriter;
        this.storage = storage;
        this.audioEnergyAnalyzer = audioEnergyAnalyzer;
        this.transcriptDetector = transcriptDetector;
        this.audioDetector = audioDetector;
        this.contextDetector = contextDetector;
        this.goalDiscovery = goalDiscovery;
        this.assembler = assembler;
        this.eventClusterer = eventClusterer;
        this.settings = settings;
        this.clock = clock;
    }

    public void process(UUID runId) {
        Instant startedAtTime = clock.instant();
        if (!detectionRuns.markRunning(runId, startedAtTime)) {
            log.warn("Skipping detection run that is no longer pending runId={}", runId);
            return;
        }

        DetectionRun run = detectionRuns.findById(runId).orElse(null);
        if (run == null) {
            log.error("Detection run disappeared after being claimed runId={}", runId);
            detectionRuns.markFailed(runId, clock.instant(),
                    "Detection run disappeared after it was claimed");
            return;
        }
        UUID mediaAssetId = run.mediaAssetId();
        long startedAtNanos = System.nanoTime();
        int transcriptSignalCount = 0;
        int audioSignalCount = 0;
        int candidateCount = 0;
        try {
            MediaAsset asset = mediaAssets.findById(mediaAssetId)
                    .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
            Transcript transcript = transcripts.findByMediaAssetId(mediaAssetId)
                    .filter(value -> value.getStatus() == TranscriptStatus.COMPLETED)
                    .orElseThrow(() -> new IllegalStateException("Completed transcript is unavailable"));
            String storageKey = asset.getLocalStoragePath();
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalStateException("Extracted audio is unavailable for candidate detection");
            }
            Path audioPath = storage.audioPath(storageKey);
            AudioEnergyAnalyzer.AudioEnergyAnalysis audioAnalysis =
                    audioEnergyAnalyzer.analyze(audioPath, settings.audioWindowMs());
            if (audioAnalysis.durationMs() == 0) {
                throw new IllegalStateException("Extracted audio contains no samples");
            }
            List<CandidateSignalObservation> transcriptObservations = transcriptDetector.detect(transcript);
            List<CandidateSignalObservation> contextObservations = contextDetector.detect(transcript);
            List<CandidateSignalObservation> outcomeObservations =
                    new TranscriptOutcomeSignalDetector().detect(transcript);
            List<CandidateSignalObservation> audioObservations = audioDetector.detect(audioAnalysis);
            transcriptSignalCount = signalCount(transcriptObservations) + signalCount(contextObservations)
                    + signalCount(outcomeObservations);
            audioSignalCount = signalCount(audioObservations);
            List<CandidateSignalObservation> observations = new ArrayList<>(
                    transcriptObservations.size() + contextObservations.size()
                            + outcomeObservations.size() + audioObservations.size());
            observations.addAll(transcriptObservations);
            observations.addAll(contextObservations);
            observations.addAll(outcomeObservations);
            observations.addAll(audioObservations);
            List<CandidateSignalObservation> discoveredGoalObservations = goalDiscovery.discover(observations);
            observations.addAll(discoveredGoalObservations);
            List<com.clipai.domain.candidate.CandidateEvent> rawCandidates = assembler.assemble(mediaAssetId,
                    transcript, observations, audioAnalysis.durationMs(), clock.instant());
            List<com.clipai.domain.candidate.CandidateEvent> canonicalEvents = eventClusterer.cluster(rawCandidates);
            candidateCount = canonicalEvents.size();
            List<CandidateSignal> persistedObservations = observations.stream()
                    .flatMap(observation -> observation.signals().stream())
                    .distinct()
                    .toList();
            List<CandidateEvent> persistedCandidates = canonicalEvents.stream()
                    .map(event -> event.withDetectionRunId(runId)).toList();
            resultWriter.persistCompleted(runId, mediaAssetId, persistedObservations,
                    persistedCandidates, clock.instant());
            log.info("Candidate detection completed mediaAssetId={} transcriptSignalCount={} "
                            + "audioSignalCount={} rawCandidateCount={} canonicalEventCount={} runId={} durationMs={}",
                    mediaAssetId, transcriptSignalCount, audioSignalCount, rawCandidates.size(),
                    candidateCount, runId, elapsedMillis(startedAtNanos));
        } catch (Exception exception) {
            log.error("Candidate detection failed mediaAssetId={} runId={} durationMs={}",
                    mediaAssetId, runId, elapsedMillis(startedAtNanos), exception);
            markFailed(runId, exception);
        }
    }

    private void markFailed(UUID runId, Exception cause) {
        try {
            String message = cause.getMessage();
            String reason = cause.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            if (reason.length() > 1000) {
                reason = reason.substring(0, 1000);
            }
            detectionRuns.markFailed(runId, clock.instant(), reason);
        } catch (Exception exception) {
            log.error("Unable to mark candidate detection failed runId={}", runId, exception);
        }
    }

    private static int signalCount(List<CandidateSignalObservation> observations) {
        return observations.stream().mapToInt(observation -> observation.signals().size()).sum();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
