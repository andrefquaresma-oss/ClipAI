package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateObservation;

import java.util.List;
import java.util.UUID;

public final class CandidateObservationQueryService {
    private static final int MAXIMUM_LIMIT = 1000;
    private final MediaAssetRepository assets;
    private final CandidateObservationRepository observations;

    public CandidateObservationQueryService(MediaAssetRepository assets,
                                            CandidateObservationRepository observations) {
        this.assets = assets;
        this.observations = observations;
    }

    public List<CandidateObservation> find(UUID mediaAssetId, long startTimeMs, long endTimeMs, int limit) {
        return findForRun(mediaAssetId, null, startTimeMs, endTimeMs, limit, true);
    }

    public List<CandidateObservation> findForRun(UUID mediaAssetId, UUID detectionRunId,
                                                 long startTimeMs, long endTimeMs, int limit) {
        return findForRun(mediaAssetId, detectionRunId, startTimeMs, endTimeMs, limit, false);
    }

    private List<CandidateObservation> findForRun(UUID mediaAssetId, UUID detectionRunId,
                                                  long startTimeMs, long endTimeMs, int limit,
                                                  boolean allRuns) {
        var asset = assets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (startTimeMs < 0 || endTimeMs < startTimeMs
                || asset.getDurationMs() != null && endTimeMs > asset.getDurationMs()) {
            throw new IllegalArgumentException("observation range must be within the media duration");
        }
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAXIMUM_LIMIT);
        }
        return allRuns
                ? observations.findByMediaAssetIdAndTimestampRange(mediaAssetId, startTimeMs, endTimeMs, limit)
                : observations.findByMediaAssetIdAndRunIdAndTimestampRange(
                        mediaAssetId, detectionRunId, startTimeMs, endTimeMs, limit);
    }
}
