package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class CandidateEventQueryService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;

    public CandidateEventQueryService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates) {
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
    }

    public CandidateEventList list(UUID mediaAssetId, CandidateSort sort) {
        var asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        List<CandidateEvent> events = candidates.findByMediaAssetId(mediaAssetId);
        Comparator<CandidateEvent> comparator = sort == CandidateSort.TIMESTAMP
                ? Comparator.comparingLong(CandidateEvent::startTimeMs)
                    .thenComparing(Comparator.comparingDouble(CandidateEvent::score).reversed())
                : Comparator.comparingDouble(CandidateEvent::score).reversed()
                    .thenComparingLong(CandidateEvent::startTimeMs);
        return new CandidateEventList(mediaAssetId, asset.getCandidateDetectionStatus(),
                asset.getCandidateDetectionFailureReason(), events.stream().sorted(comparator).toList());
    }

    public CandidateEvent get(UUID mediaAssetId, UUID eventId) {
        mediaAssets.findById(mediaAssetId).orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        return candidates.findByIdAndMediaAssetId(eventId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(eventId, mediaAssetId));
    }
}
