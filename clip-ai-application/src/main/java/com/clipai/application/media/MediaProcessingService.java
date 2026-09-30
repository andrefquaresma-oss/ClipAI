package com.clipai.application.media;

import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.ports.MediaProcessor;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.TranscriptionService;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import com.clipai.domain.transcript.TranscriptStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class MediaProcessingService {
    private static final Logger log = LoggerFactory.getLogger(MediaProcessingService.class);
    private static final String UNKNOWN_LANGUAGE = "und";

    private final MediaAssetRepository mediaAssets;
    private final TranscriptRepository transcripts;
    private final MediaStorage storage;
    private final MediaProcessor mediaProcessor;
    private final TranscriptionService transcriptionService;
    private final CandidateDetectionScheduler candidateDetectionScheduler;
    private final ProcessingStageRepository stages;
    private final Clock clock;

    public MediaProcessingService(MediaAssetRepository mediaAssets, TranscriptRepository transcripts,
                                  MediaStorage storage, MediaProcessor mediaProcessor,
                                  TranscriptionService transcriptionService,
                                  CandidateDetectionScheduler candidateDetectionScheduler, Clock clock) {
        this(mediaAssets, transcripts, storage, mediaProcessor, transcriptionService,
                candidateDetectionScheduler, ProcessingStageRepository.none(), clock);
    }

    public MediaProcessingService(MediaAssetRepository mediaAssets, TranscriptRepository transcripts,
                                  MediaStorage storage, MediaProcessor mediaProcessor,
                                  TranscriptionService transcriptionService,
                                  CandidateDetectionScheduler candidateDetectionScheduler,
                                  ProcessingStageRepository stages, Clock clock) {
        this.mediaAssets = mediaAssets;
        this.transcripts = transcripts;
        this.storage = storage;
        this.mediaProcessor = mediaProcessor;
        this.transcriptionService = transcriptionService;
        this.candidateDetectionScheduler = candidateDetectionScheduler;
        this.stages = stages;
        this.clock = clock;
    }

    public void process(UUID mediaAssetId) {
        extractAudio(mediaAssetId);
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() != MediaAssetStatus.AUDIO_EXTRACTED) {
            return;
        }
        transcribe(mediaAssetId);
        asset = mediaAssets.findById(mediaAssetId).orElseThrow();
        if (asset.getStatus() == MediaAssetStatus.COMPLETED) {
            scheduleCandidateDetection(mediaAssetId);
        }
    }

    public void extractAudio(UUID mediaAssetId) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() == MediaAssetStatus.AUDIO_EXTRACTED
                || asset.getStatus() == MediaAssetStatus.TRANSCRIBING
                || asset.getStatus() == MediaAssetStatus.COMPLETED) {
            completeStage(mediaAssetId, ProcessingStage.AUDIO_EXTRACTION, "Audio already extracted");
            return;
        }
        if (asset.getStatus() != MediaAssetStatus.STORED && asset.getStatus() != MediaAssetStatus.READY) {
            log.warn("Skipping audio extraction for unexpected status mediaAssetId={} status={}",
                    mediaAssetId, asset.getStatus());
            return;
        }

        long startedAt = System.nanoTime();
        stage(mediaAssetId, ProcessingStage.AUDIO_EXTRACTION, ProcessingStageStatus.RUNNING, 0,
                "Extracting audio");
        try {
            asset.startProcessing(clock.instant());
            asset = mediaAssets.save(asset);
            Path videoPath = storage.resolve(asset.getLocalStoragePath());
            Path audioPath = storage.audioPath(asset.getLocalStoragePath());
            mediaProcessor.process(videoPath, audioPath);
            asset.markAudioExtracted(clock.instant());
            mediaAssets.save(asset);
            completeStage(mediaAssetId, ProcessingStage.AUDIO_EXTRACTION, "Audio extraction completed");
            log.info("Audio extraction completed mediaAssetId={} durationMs={}",
                    mediaAssetId, elapsedMillis(startedAt));
        } catch (Exception exception) {
            failProcessing(mediaAssetId, exception);
            stage(mediaAssetId, ProcessingStage.AUDIO_EXTRACTION, ProcessingStageStatus.FAILED, null,
                    failureReason(exception));
        }
    }

    public void transcribe(UUID mediaAssetId) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() == MediaAssetStatus.COMPLETED) {
            completeStage(mediaAssetId, ProcessingStage.TRANSCRIPTION, "Transcription already completed");
            return;
        }
        if (asset.getStatus() != MediaAssetStatus.AUDIO_EXTRACTED) {
            log.warn("Skipping transcription for unexpected status mediaAssetId={} status={}",
                    mediaAssetId, asset.getStatus());
            return;
        }

        long startedAt = System.nanoTime();
        Transcript transcript = null;
        stage(mediaAssetId, ProcessingStage.TRANSCRIPTION, ProcessingStageStatus.RUNNING, 0,
                "Transcribing audio");
        try {
            asset.startTranscribing(clock.instant());
            mediaAssets.save(asset);
            transcript = transcripts.findByMediaAssetId(mediaAssetId)
                    .orElseGet(() -> Transcript.create(mediaAssetId, UNKNOWN_LANGUAGE, clock.instant()));
            if (transcript.getStatus() == TranscriptStatus.FAILED) {
                transcript.resetForRetry(clock.instant());
            }
            transcript.startProcessing(clock.instant());
            transcript = transcripts.save(transcript);

            Path audioPath = storage.audioPath(asset.getLocalStoragePath());
            var result = transcriptionService.transcribe(audioPath, asset.getLanguage());
            List<TranscriptionService.TranscribedSegment> orderedSegments = result.segments().stream()
                    .sorted(Comparator.comparingLong(TranscriptionService.TranscribedSegment::startTimeMs)
                            .thenComparingLong(TranscriptionService.TranscribedSegment::endTimeMs))
                    .toList();
            for (int sequence = 0; sequence < orderedSegments.size(); sequence++) {
                var segment = orderedSegments.get(sequence);
                transcript.addSegment(TranscriptSegment.create(transcript.getId(), sequence,
                        segment.startTimeMs(), segment.endTimeMs(), segment.text()));
            }
            transcript.markCompleted(result.language(), clock.instant());
            transcript = transcripts.save(transcript);
            asset.markCompleted(clock.instant());
            mediaAssets.save(asset);
            completeStage(mediaAssetId, ProcessingStage.TRANSCRIPTION, "Transcription completed");
            log.info("Transcription completed mediaAssetId={} transcriptId={} durationMs={} segmentCount={}",
                    mediaAssetId, transcript.getId(), elapsedMillis(startedAt),
                    transcript.getSegments().size());
        } catch (Exception exception) {
            failProcessing(mediaAssetId, exception);
            stage(mediaAssetId, ProcessingStage.TRANSCRIPTION, ProcessingStageStatus.FAILED, null,
                    failureReason(exception));
        }
    }

    public void retry(UUID mediaAssetId) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        asset.retryProcessing(clock.instant());
        mediaAssets.save(asset);
        transcripts.findByMediaAssetId(mediaAssetId)
                .filter(transcript -> transcript.getStatus() == TranscriptStatus.FAILED)
                .ifPresent(transcript -> transcripts.save(transcript));
        queueStage(mediaAssetId, ProcessingStage.AUDIO_EXTRACTION);
        queueStage(mediaAssetId, ProcessingStage.TRANSCRIPTION);
        process(mediaAssetId);
    }

    private void scheduleCandidateDetection(UUID mediaAssetId) {
        try {
            candidateDetectionScheduler.schedule(mediaAssetId);
        } catch (RuntimeException exception) {
            log.error("Unable to schedule candidate detection after transcription mediaAssetId={}",
                    mediaAssetId, exception);
        }
    }

    private void failProcessing(UUID mediaAssetId, Exception exception) {
        String reason = failureReason(exception);
        log.error("Media processing failed mediaAssetId={} reason={}", mediaAssetId, reason, exception);
        mediaAssets.findById(mediaAssetId).ifPresent(asset -> {
            if (asset.getStatus() != MediaAssetStatus.COMPLETED && asset.getStatus() != MediaAssetStatus.FAILED) {
                asset.markFailed(reason, clock.instant());
                mediaAssets.save(asset);
            }
        });
        transcripts.findByMediaAssetId(mediaAssetId).ifPresent(transcript -> {
            if (transcript.getStatus() != TranscriptStatus.COMPLETED
                    && transcript.getStatus() != TranscriptStatus.FAILED) {
                transcript.markFailed(reason, clock.instant());
                transcripts.save(transcript);
            }
        });
    }

    private void queueStage(UUID id, ProcessingStage stage) {
        stage(id, stage, ProcessingStageStatus.QUEUED, 0, "Queued");
    }

    private void completeStage(UUID id, ProcessingStage stage, String message) {
        stage(id, stage, ProcessingStageStatus.COMPLETED, 100, message);
    }

    private void stage(UUID id, ProcessingStage stage, ProcessingStageStatus status,
                       Integer progress, String message) {
        stages.save(new ProcessingStageRun(id, stage, status, progress, message, clock.instant()));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String failureReason(Exception exception) {
        if (exception instanceof ProcessingFailureException processingFailure) {
            return processingFailure.publicReason();
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Media processing failed";
        }
        String normalized = message.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }
}
