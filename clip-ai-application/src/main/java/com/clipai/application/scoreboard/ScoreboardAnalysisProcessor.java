package com.clipai.application.scoreboard;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.MediaStorage;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.scoreboard.ScoreboardAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;

public final class ScoreboardAnalysisProcessor {
    private static final Logger log = LoggerFactory.getLogger(ScoreboardAnalysisProcessor.class);
    private final ScoreboardAnalysisRepository analyses;
    private final MediaAssetRepository mediaAssets;
    private final MediaStorage storage;
    private final ScoreboardOcrService ocr;
    private final Clock clock;

    public ScoreboardAnalysisProcessor(ScoreboardAnalysisRepository analyses,
                                       MediaAssetRepository mediaAssets, MediaStorage storage,
                                       ScoreboardOcrService ocr, Clock clock) {
        this.analyses = analyses;
        this.mediaAssets = mediaAssets;
        this.storage = storage;
        this.ocr = ocr;
        this.clock = clock;
    }

    public void process(UUID analysisId) {
        if (!analyses.markRunning(analysisId, clock.instant())) {
            log.warn("Skipping scoreboard analysis that is no longer pending analysisId={}", analysisId);
            return;
        }
        ScoreboardAnalysis analysis = findAnalysis(analysisId);
        long startedAtNanos = System.nanoTime();
        try {
            MediaAsset asset = mediaAssets.findById(analysis.mediaAssetId())
                    .orElseThrow(() -> new MediaAssetNotFoundException(analysis.mediaAssetId()));
            var source = storage.resolve(asset.getLocalStoragePath());
            ScoreboardOcrResult result = ocr.analyze(source, analysis.id());
            ScoreboardAnalysis completed = new ScoreboardAnalysis(analysis.id(), analysis.mediaAssetId(),
                    com.clipai.domain.scoreboard.ScoreboardAnalysisStatus.COMPLETED,
                    analysis.createdAt(), analysis.startedAt(), clock.instant(),
                    result.modelVersion(), result.sampledFrameCount(), result.ocrCallCount(),
                    result.rawObservationCount(), result.scoreStateCount(), result.scoreTransitionCount(),
                    result.scoreReversalCount(), result.processingErrorCount(),
                    result.processingDurationMs(), null);
            analyses.complete(completed, result.observations());
            log.info("Scoreboard OCR completed mediaAssetId={} analysisId={} samples={} calls={} "
                            + "rawObservations={} states={} transitions={} reversals={} errors={} durationMs={}",
                    analysis.mediaAssetId(), analysis.id(), result.sampledFrameCount(), result.ocrCallCount(),
                    result.rawObservationCount(), result.scoreStateCount(), result.scoreTransitionCount(),
                    result.scoreReversalCount(), result.processingErrorCount(), result.processingDurationMs());
        } catch (Exception exception) {
            String message = exception.getMessage();
            String reason = exception.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            if (reason.length() > 1000) {
                reason = reason.substring(0, 1000);
            }
            analyses.fail(analysisId, clock.instant(), reason);
            log.error("Scoreboard OCR failed mediaAssetId={} analysisId={} elapsedMs={}",
                    analysis.mediaAssetId(), analysisId,
                    (System.nanoTime() - startedAtNanos) / 1_000_000, exception);
        }
    }

    private ScoreboardAnalysis findAnalysis(UUID analysisId) {
        return analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("Scoreboard analysis disappeared after claim"));
    }
}
