package com.clipai.application.matchcontext;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;

import java.time.Clock;
import java.util.UUID;

public final class MatchContextService {
    private final MediaAssetRepository assets;
    private final MatchContextRepository context;
    private final Clock clock;

    public MatchContextService(MediaAssetRepository assets, MatchContextRepository context, Clock clock) {
        this.assets = assets;
        this.context = context;
        this.clock = clock;
    }

    public MatchContext get(UUID mediaAssetId) {
        requireAsset(mediaAssetId);
        return new MatchContext(context.findStructureMarkers(mediaAssetId),
                context.findScoreTransitions(mediaAssetId));
    }

    public MatchPhase phaseAt(UUID mediaAssetId, long timestampMs) {
        return MatchPhaseResolver.at(get(mediaAssetId).structureMarkers(), timestampMs);
    }

    public MatchStructureMarker setStructureMarker(UUID mediaAssetId, MatchStructureMarkerType type,
                                                   long timestampMs) {
        var asset = requireAsset(mediaAssetId);
        if (type == null) {
            throw new IllegalArgumentException("structure marker type is required");
        }
        validateTimestamp(asset.getDurationMs(), timestampMs);
        return context.saveStructureMarker(new MatchStructureMarker(mediaAssetId, type,
                timestampMs, clock.instant()));
    }

    public void deleteStructureMarker(UUID mediaAssetId, MatchStructureMarkerType type) {
        requireAsset(mediaAssetId);
        if (type == null) {
            throw new IllegalArgumentException("structure marker type is required");
        }
        context.deleteStructureMarker(mediaAssetId, type);
    }

    public MatchScoreTransition createScoreTransition(UUID mediaAssetId, long timestampMs,
                                                      int homeScore, int awayScore,
                                                      ScoreTransitionSource source, Double confidence) {
        var asset = requireAsset(mediaAssetId);
        validateScore(timestampMs, homeScore, awayScore, source, confidence, asset.getDurationMs());
        var now = clock.instant();
        return context.saveScoreTransition(new MatchScoreTransition(UUID.randomUUID(), mediaAssetId,
                timestampMs, homeScore, awayScore, source, confidence, now, now));
    }

    public MatchScoreTransition updateScoreTransition(UUID mediaAssetId, UUID transitionId,
                                                      long timestampMs, int homeScore, int awayScore,
                                                      ScoreTransitionSource source, Double confidence) {
        var asset = requireAsset(mediaAssetId);
        validateScore(timestampMs, homeScore, awayScore, source, confidence, asset.getDurationMs());
        MatchScoreTransition existing = context.findScoreTransitions(mediaAssetId).stream()
                .filter(item -> item.id().equals(transitionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("score transition not found"));
        return context.saveScoreTransition(new MatchScoreTransition(transitionId, mediaAssetId,
                timestampMs, homeScore, awayScore, source, confidence,
                existing.createdAt(), clock.instant()));
    }

    public void deleteScoreTransition(UUID mediaAssetId, UUID transitionId) {
        requireAsset(mediaAssetId);
        context.deleteScoreTransition(mediaAssetId, transitionId);
    }

    private com.clipai.domain.media.MediaAsset requireAsset(UUID mediaAssetId) {
        return assets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
    }

    private static void validateTimestamp(Long durationMs, long timestampMs) {
        if (timestampMs < 0 || durationMs != null && timestampMs > durationMs) {
            throw new IllegalArgumentException("timestamp must be within the media duration");
        }
    }

    private static void validateScore(long timestampMs, int homeScore, int awayScore,
                                      ScoreTransitionSource source, Double confidence, Long durationMs) {
        validateTimestamp(durationMs, timestampMs);
        if (homeScore < 0 || awayScore < 0 || source == null) {
            throw new IllegalArgumentException("scores must be non-negative and source is required");
        }
        if (confidence != null && (confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
