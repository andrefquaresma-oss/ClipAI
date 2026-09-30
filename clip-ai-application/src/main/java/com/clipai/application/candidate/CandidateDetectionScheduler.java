package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.candidate.CandidateDetectionStatus;
import com.clipai.domain.candidate.DetectionRun;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.TranscriptStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class CandidateDetectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(CandidateDetectionScheduler.class);
    private final MediaAssetRepository mediaAssets;
    private final TranscriptRepository transcripts;
    private final DetectionRunRepository detectionRuns;
    private final DetectionRunDescriptor runDescriptor;
    private final CandidateDetectionTrigger trigger;
    private final Clock clock;

    public CandidateDetectionScheduler(MediaAssetRepository mediaAssets, TranscriptRepository transcripts,
                                       DetectionRunRepository detectionRuns,
                                       DetectionRunDescriptor runDescriptor,
                                       CandidateDetectionTrigger trigger, Clock clock) {
        this.mediaAssets = mediaAssets;
        this.transcripts = transcripts;
        this.detectionRuns = detectionRuns;
        this.runDescriptor = runDescriptor;
        this.trigger = trigger;
        this.clock = clock;
    }

    public MediaAsset schedule(UUID mediaAssetId) {
        scheduleRun(mediaAssetId);
        return mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
    }

    public DetectionRun scheduleRun(UUID mediaAssetId) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() != MediaAssetStatus.COMPLETED
                || transcripts.findByMediaAssetId(mediaAssetId)
                .filter(transcript -> transcript.getStatus() == TranscriptStatus.COMPLETED).isEmpty()) {
            throw new CandidateDetectionConflictException(mediaAssetId,
                    "Candidate detection requires completed media and transcript processing");
        }
        if (asset.getCandidateDetectionStatus() == CandidateDetectionStatus.PROCESSING) {
            throw new CandidateDetectionConflictException(mediaAssetId, "Candidate detection is already processing");
        }

        Instant requestedAt = clock.instant();
        DetectionRun run = DetectionRun.pending(mediaAssetId, requestedAt,
                runDescriptor.detectorVersion(), runDescriptor.configurationHash());
        if (!detectionRuns.createPending(run)) {
            throw new CandidateDetectionConflictException(mediaAssetId, "Candidate detection is already processing");
        }
        try {
            asset.startCandidateDetection(requestedAt);
            mediaAssets.save(asset);
            trigger.schedule(run.id());
            log.info("Candidate detection run scheduled mediaAssetId={} runId={} detectorVersion={}",
                    mediaAssetId, run.id(), run.detectorVersion());
            return detectionRuns.findById(run.id()).orElse(run);
        } catch (RuntimeException exception) {
            detectionRuns.markFailed(run.id(), clock.instant(),
                    "Candidate detection could not be scheduled: " + exception.getClass().getSimpleName());
            log.error("Candidate detection scheduling failed mediaAssetId={} runId={}",
                    mediaAssetId, run.id(), exception);
            throw new CandidateDetectionSchedulingException(mediaAssetId, exception);
        }
    }
}
