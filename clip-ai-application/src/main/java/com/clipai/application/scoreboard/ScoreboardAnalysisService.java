package com.clipai.application.scoreboard;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.scoreboard.ScoreboardAnalysis;
import com.clipai.domain.scoreboard.ScoreboardAnalysisStatus;
import com.clipai.domain.scoreboard.ScoreboardObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ScoreboardAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ScoreboardAnalysisService.class);
    private final MediaAssetRepository mediaAssets;
    private final ScoreboardAnalysisRepository analyses;
    private final ScoreboardAnalysisTrigger trigger;
    private final Clock clock;
    private final String modelVersion;
    private final boolean enabled;

    public ScoreboardAnalysisService(MediaAssetRepository mediaAssets,
                                     ScoreboardAnalysisRepository analyses,
                                     ScoreboardAnalysisTrigger trigger, Clock clock,
                                     String modelVersion, boolean enabled) {
        this.mediaAssets = mediaAssets;
        this.analyses = analyses;
        this.trigger = trigger;
        this.clock = clock;
        this.modelVersion = modelVersion;
        this.enabled = enabled;
    }

    public ScoreboardAnalysis start(UUID mediaAssetId) {
        if (!enabled) {
            throw new IllegalStateException("Scoreboard OCR is disabled by configuration");
        }
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() != MediaAssetStatus.COMPLETED
                || asset.getLocalStoragePath() == null || asset.getLocalStoragePath().isBlank()) {
            throw new IllegalStateException("Scoreboard analysis requires a completed video asset");
        }
        ScoreboardAnalysis analysis = new ScoreboardAnalysis(UUID.randomUUID(), mediaAssetId,
                ScoreboardAnalysisStatus.PENDING, clock.instant(), null, null, modelVersion,
                0, 0, 0, 0, 0, 0, 0, 0, null);
        analyses.create(analysis);
        try {
            trigger.schedule(analysis.id());
            log.info("Scoreboard OCR analysis scheduled mediaAssetId={} analysisId={} model={}",
                    mediaAssetId, analysis.id(), modelVersion);
            return get(mediaAssetId, analysis.id());
        } catch (RuntimeException exception) {
            analyses.fail(analysis.id(), clock.instant(),
                    "Scoreboard analysis could not be scheduled: " + exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public ScoreboardAnalysis get(UUID mediaAssetId, UUID analysisId) {
        return analyses.find(mediaAssetId, analysisId)
                .orElseThrow(() -> new ScoreboardAnalysisNotFoundException(mediaAssetId, analysisId));
    }

    public List<ScoreboardAnalysis> list(UUID mediaAssetId) {
        if (mediaAssets.findById(mediaAssetId).isEmpty()) {
            throw new MediaAssetNotFoundException(mediaAssetId);
        }
        return analyses.findByMediaAssetId(mediaAssetId);
    }

    public List<ScoreboardObservation> observations(UUID mediaAssetId, UUID analysisId,
                                                    long startTimeMs, long endTimeMs, int limit) {
        get(mediaAssetId, analysisId);
        if (startTimeMs < 0 || endTimeMs < startTimeMs || limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("Invalid timestamp range or limit");
        }
        return analyses.observations(mediaAssetId, analysisId, startTimeMs, endTimeMs, limit);
    }
}
