package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.MediaAsset;

import java.time.Clock;
import java.util.UUID;

public final class ManualCandidateEventService {
    private final MediaAssetRepository assets;
    private final CandidateEventRepository candidates;
    private final Clock clock;
    private final long maximumClipDurationMs;

    public ManualCandidateEventService(MediaAssetRepository assets, CandidateEventRepository candidates,
                                       Clock clock, long maximumClipDurationMs) {
        if (maximumClipDurationMs < 1) {
            throw new IllegalArgumentException("maximumClipDurationMs must be positive");
        }
        this.assets = assets;
        this.candidates = candidates;
        this.clock = clock;
        this.maximumClipDurationMs = maximumClipDurationMs;
    }

    public CandidateEvent create(UUID mediaAssetId, FootballEventType eventType, long timestampMs,
                                 long startTimeMs, long endTimeMs, String note) {
        MediaAsset asset = assets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (startTimeMs < 0 || endTimeMs <= startTimeMs || timestampMs < startTimeMs
                || timestampMs > endTimeMs) {
            throw new IllegalArgumentException("event timestamp must be inside a valid non-negative clip window");
        }
        if (endTimeMs - startTimeMs > maximumClipDurationMs) {
            throw new IllegalArgumentException("manual event window exceeds maximum clip duration");
        }
        if (asset.getDurationMs() != null && endTimeMs > asset.getDurationMs()) {
            throw new IllegalArgumentException("manual event window exceeds media duration");
        }
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("note must be at most 2000 characters");
        }
        return candidates.saveManual(CandidateEvent.manual(mediaAssetId, startTimeMs, endTimeMs,
                timestampMs, eventType, note, clock.instant()));
    }
}
