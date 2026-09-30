package com.clipai.application.media;

import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.TranscriptStatus;

import java.time.Clock;
import java.util.UUID;

public final class MediaProcessingControlService {
    private final MediaAssetRepository assets;
    private final TranscriptRepository transcripts;
    private final ProcessingStageRepository stages;
    private final MediaProcessingTrigger trigger;
    private final Clock clock;

    public MediaProcessingControlService(MediaAssetRepository assets, TranscriptRepository transcripts,
                                         ProcessingStageRepository stages, MediaProcessingTrigger trigger,
                                         Clock clock) {
        this.assets = assets;
        this.transcripts = transcripts;
        this.stages = stages;
        this.trigger = trigger;
        this.clock = clock;
    }

    public void start(UUID id) {
        MediaAsset asset = requireAsset(id);
        if (asset.getStatus() != MediaAssetStatus.STORED && asset.getStatus() != MediaAssetStatus.READY) {
            throw new IllegalStateException("Full processing can only start for a stored or ready media asset");
        }
        queue(id, ProcessingStage.AUDIO_EXTRACTION);
        queue(id, ProcessingStage.TRANSCRIPTION);
        trigger.schedule(id);
    }

    public void startAudioExtraction(UUID id) {
        MediaAsset asset = requireAsset(id);
        if (asset.getStatus() != MediaAssetStatus.STORED && asset.getStatus() != MediaAssetStatus.READY) {
            throw new IllegalStateException("Audio extraction requires a stored or ready media asset");
        }
        queue(id, ProcessingStage.AUDIO_EXTRACTION);
        trigger.scheduleAudioExtraction(id);
    }

    public void startTranscription(UUID id) {
        MediaAsset asset = requireAsset(id);
        if (asset.getStatus() != MediaAssetStatus.AUDIO_EXTRACTED) {
            throw new IllegalStateException("Transcription requires extracted audio");
        }
        queue(id, ProcessingStage.TRANSCRIPTION);
        trigger.scheduleTranscription(id);
    }

    public void retry(UUID id) {
        MediaAsset asset = requireAsset(id);
        if (asset.getStatus() != MediaAssetStatus.FAILED) {
            throw new IllegalStateException("Only failed media processing can be retried");
        }
        queue(id, ProcessingStage.AUDIO_EXTRACTION);
        queue(id, ProcessingStage.TRANSCRIPTION);
        trigger.scheduleRetry(id);
    }

    public MediaProcessingStatus getStatus(UUID id) {
        MediaAsset asset = requireAsset(id);
        var transcript = transcripts.findByMediaAssetId(id).orElse(null);
        return new MediaProcessingStatus(id, asset.getStatus(),
                transcript == null ? null : transcript.getStatus(),
                asset.getCandidateDetectionStatus(), stages.findByMediaAssetId(id),
                asset.getFailureReason(), asset.getUpdatedAt());
    }

    private MediaAsset requireAsset(UUID id) {
        return assets.findById(id).orElseThrow(() -> new MediaAssetNotFoundException(id));
    }

    private void queue(UUID id, ProcessingStage stage) {
        stages.save(new ProcessingStageRun(id, stage, ProcessingStageStatus.QUEUED, 0,
                "Queued", clock.instant()));
    }
}
