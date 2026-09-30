package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateObservation;
import com.clipai.domain.candidate.DetectionRun;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DetectionRunQueryService {
    private static final String LEGACY_RUN_ID = "legacy";
    private final MediaAssetRepository assets;
    private final DetectionRunRepository runs;
    private final CandidateEventRepository candidates;
    private final CandidateObservationRepository observations;
    private final CandidateObservationQueryService observationQueries;

    public DetectionRunQueryService(MediaAssetRepository assets, DetectionRunRepository runs,
                                    CandidateEventRepository candidates,
                                    CandidateObservationRepository observations,
                                    CandidateObservationQueryService observationQueries) {
        this.assets = assets;
        this.runs = runs;
        this.candidates = candidates;
        this.observations = observations;
        this.observationQueries = observationQueries;
    }

    public List<DetectionRunSummary> list(UUID mediaAssetId) {
        requireAsset(mediaAssetId);
        List<DetectionRunSummary> summaries = new ArrayList<>(runs.findByMediaAssetId(mediaAssetId).stream()
                .map(DetectionRunSummary::from).toList());
        List<CandidateEvent> legacyCandidates = candidates.findLegacyByMediaAssetId(mediaAssetId);
        long legacyObservations = observations.countByMediaAssetIdAndRunId(mediaAssetId, null);
        if (!legacyCandidates.isEmpty() || legacyObservations > 0) {
            int detected = (int) legacyCandidates.stream()
                    .filter(candidate -> candidate.status() == CandidateEventStatus.DETECTED).count();
            int rejected = (int) legacyCandidates.stream()
                    .filter(candidate -> candidate.status() == CandidateEventStatus.REJECTED).count();
            summaries.add(DetectionRunSummary.legacy(mediaAssetId, legacyCandidates.size(),
                    Math.toIntExact(legacyObservations), detected, rejected));
        }
        return List.copyOf(summaries);
    }

    public DetectionRunSummary get(UUID mediaAssetId, String runKey) {
        requireAsset(mediaAssetId);
        if (LEGACY_RUN_ID.equalsIgnoreCase(runKey)) {
            return list(mediaAssetId).stream().filter(DetectionRunSummary::legacy).findFirst()
                    .orElseThrow(() -> new DetectionRunNotFoundException(runKey));
        }
        DetectionRun run = findRun(mediaAssetId, runKey);
        return DetectionRunSummary.from(run);
    }

    public List<CandidateEvent> candidates(UUID mediaAssetId, String runKey) {
        requireAsset(mediaAssetId);
        if (LEGACY_RUN_ID.equalsIgnoreCase(runKey)) {
            get(mediaAssetId, runKey);
            return candidates.findLegacyByMediaAssetId(mediaAssetId);
        }
        DetectionRun run = findRun(mediaAssetId, runKey);
        return candidates.findByDetectionRunId(run.id());
    }

    public List<CandidateObservation> observations(UUID mediaAssetId, String runKey,
                                                   long startTimeMs, long endTimeMs, int limit) {
        UUID runId = LEGACY_RUN_ID.equalsIgnoreCase(runKey) ? null : findRun(mediaAssetId, runKey).id();
        if (runId == null) {
            get(mediaAssetId, runKey);
        }
        return observationQueries.findForRun(mediaAssetId, runId, startTimeMs, endTimeMs, limit);
    }

    DetectionRun findRun(UUID mediaAssetId, String runKey) {
        UUID runId;
        try {
            runId = UUID.fromString(runKey);
        } catch (IllegalArgumentException exception) {
            throw new DetectionRunNotFoundException(runKey);
        }
        return runs.findById(runId)
                .filter(run -> run.mediaAssetId().equals(mediaAssetId))
                .orElseThrow(() -> new DetectionRunNotFoundException(runKey));
    }

    private void requireAsset(UUID mediaAssetId) {
        assets.findById(mediaAssetId).orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
    }
}
