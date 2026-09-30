package com.clipai.application.media;

import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.Transcript;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaProcessingControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void queuesIndependentAudioStageAndRequiresAudioBeforeTranscription() {
        MediaAsset stored = MediaAsset.registerUpload("LOCAL_UPLOAD", "match.mp4", ContentType.SPORTS, NOW);
        stored.markStored("media/match.mp4", NOW);
        AtomicReference<MediaAsset> asset = new AtomicReference<>(stored);
        List<ProcessingStageRun> runs = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        ProcessingStageRepository stages = new ProcessingStageRepository() {
            @Override public List<ProcessingStageRun> findByMediaAssetId(UUID id) { return List.copyOf(runs); }
            @Override public ProcessingStageRun save(ProcessingStageRun run) {
                runs.removeIf(existing -> existing.stage() == run.stage());
                runs.add(run); return run;
            }
        };
        MediaProcessingTrigger trigger = new MediaProcessingTrigger() {
            @Override public void schedule(UUID id) { calls.add("full"); }
            @Override public void scheduleRetry(UUID id) { calls.add("retry"); }
            @Override public void scheduleAudioExtraction(UUID id) { calls.add("audio"); }
            @Override public void scheduleTranscription(UUID id) { calls.add("transcription"); }
        };
        MediaAssetRepository assets = new MediaAssetRepository() {
            @Override public MediaAsset save(MediaAsset value) { asset.set(value); return value; }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return asset.get().getId().equals(id) ? Optional.of(asset.get()) : Optional.empty();
            }
            @Override public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                return new MediaAssetPage(List.of(asset.get()), page, size, 1);
            }
        };
        TranscriptRepository transcripts = new TranscriptRepository() {
            @Override public Transcript save(Transcript transcript) { return transcript; }
            @Override public Optional<Transcript> findByMediaAssetId(UUID id) { return Optional.empty(); }
        };
        MediaProcessingControlService service = new MediaProcessingControlService(assets, transcripts,
                stages, trigger, Clock.fixed(NOW, ZoneOffset.UTC));

        service.startAudioExtraction(stored.getId());
        assertEquals(List.of("audio"), calls);
        assertEquals(ProcessingStageStatus.QUEUED, service.getStatus(stored.getId()).stages().getFirst().status());
        assertThrows(IllegalStateException.class, () -> service.startTranscription(stored.getId()));

        MediaAsset extracted = MediaAsset.restore(stored.getId(), "LOCAL_UPLOAD", null, null,
                "match.mp4", ContentType.SPORTS, null, "media/match.mp4", MediaAssetStatus.AUDIO_EXTRACTED,
                NOW, NOW);
        asset.set(extracted);
        service.startTranscription(stored.getId());
        assertEquals(List.of("audio", "transcription"), calls);
        assertEquals(ProcessingStage.TRANSCRIPTION, runs.getLast().stage());

        extracted.markFailed("Transcription service failed", NOW);
        asset.set(extracted);
        service.retry(stored.getId());
        assertEquals(List.of("audio", "transcription", "retry"), calls);
    }
}
