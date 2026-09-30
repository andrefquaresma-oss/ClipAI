package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateObservation;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.media.MediaAsset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DetectionRunComparisonService {
    private static final long DEFAULT_OBSERVATION_WINDOW_MS = 60_000;
    private static final long MAXIMUM_OBSERVATION_WINDOW_MS = 300_000;
    private static final int OBSERVATION_LIMIT = 1000;

    private final MediaAssetRepository assets;
    private final DetectionRunQueryService runs;
    private final DetectionRunCandidateMatcher matcher;

    public DetectionRunComparisonService(MediaAssetRepository assets,
                                         DetectionRunQueryService runs,
                                         DetectionRunCandidateMatcher matcher) {
        this.assets = assets;
        this.runs = runs;
        this.matcher = matcher;
    }

    public DetectionRunComparison compare(UUID mediaAssetId, String leftRunKey, String rightRunKey,
                                          Long centerTimestampMs, Long requestedWindowMs) {
        DetectionRunSummary leftSummary = runs.get(mediaAssetId, leftRunKey);
        DetectionRunSummary rightSummary = runs.get(mediaAssetId, rightRunKey);
        if (leftSummary.runId().equals(rightSummary.runId())) {
            throw new IllegalArgumentException("leftRunId and rightRunId must be different");
        }
        DetectionRunCandidateMatcher.Result candidateMatches =
                matcher.match(runs.candidates(mediaAssetId, leftRunKey),
                        runs.candidates(mediaAssetId, rightRunKey));
        DetectionRunComparison.ObservationComparison observations = null;
        if (centerTimestampMs != null || requestedWindowMs != null) {
            if (centerTimestampMs == null || centerTimestampMs < 0) {
                throw new IllegalArgumentException("centerTimestampMs must be non-negative");
            }
            long windowMs = requestedWindowMs == null ? DEFAULT_OBSERVATION_WINDOW_MS : requestedWindowMs;
            if (windowMs < 1 || windowMs > MAXIMUM_OBSERVATION_WINDOW_MS) {
                throw new IllegalArgumentException("windowMs must be between 1 and "
                        + MAXIMUM_OBSERVATION_WINDOW_MS);
            }
            long start = Math.max(0, centerTimestampMs - windowMs);
            long end;
            try {
                end = Math.addExact(centerTimestampMs, windowMs);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("observation window exceeds supported timestamps", exception);
            }
            MediaAsset asset = assets.findById(mediaAssetId)
                    .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
            if (asset.getDurationMs() != null) {
                end = Math.min(end, asset.getDurationMs());
            }
            observations = new DetectionRunComparison.ObservationComparison(centerTimestampMs, windowMs,
                    group(runs.observations(mediaAssetId, leftRunKey, start, end, OBSERVATION_LIMIT)),
                    group(runs.observations(mediaAssetId, rightRunKey, start, end, OBSERVATION_LIMIT)));
        }
        return new DetectionRunComparison(leftSummary, rightSummary, candidateMatches.matches(),
                candidateMatches.onlyLeft(), candidateMatches.onlyRight(), observations);
    }

    private static Map<String, List<CandidateObservation>> group(List<CandidateObservation> values) {
        Map<String, List<CandidateObservation>> result = new LinkedHashMap<>();
        for (CandidateObservation observation : values) {
            String family = family(observation.signal().type());
            result.computeIfAbsent(family, ignored -> new java.util.ArrayList<>()).add(observation);
        }
        Map<String, List<CandidateObservation>> ordered = new LinkedHashMap<>();
        result.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), List.copyOf(entry.getValue())));
        return Map.copyOf(ordered);
    }

    private static String family(CandidateSignalType type) {
        return switch (type) {
            case TRANSCRIPT_KEYWORD, TRANSCRIPT_EMPHASIS, TRANSCRIPT_REPETITION, TRANSCRIPT_GOAL,
                    TRANSCRIPT_SHOT, TRANSCRIPT_PENALTY, TRANSCRIPT_CARD, TRANSCRIPT_EVENT ->
                    "TRANSCRIPT";
            case ATTACK_BUILDUP, LIVE_EVENT_CONTEXT -> "ATTACK_BUILDUP";
            case SPEECH_RATE_SPIKE -> "SPEECH_RATE";
            case PITCH_RISE, PITCH_VARIANCE, VOICE_EXCITEMENT, HIGH_EXCITEMENT -> "PITCH";
            case AUDIO_SPIKE, AUDIO_SUSTAINED, AUDIO_ENERGY_RISE, WHISTLE_LIKE_AUDIO ->
                    "AUDIO_ENERGY";
            case CROWD_REACTION_PROXY -> "CROWD_REACTION";
            case REPLAY_CONTEXT, EVENT_ASSOCIATION -> "REPLAY";
            case RETROSPECTIVE_CONTEXT, POST_EVENT_COMMENTARY, UNRELATED_ACTION_CONTEXT ->
                    "RETROSPECTIVE";
            case RESTART_CONTEXT, GOAL_KICK_CONTEXT -> "RESTART";
            case SHOT_OUTCOME_CONTEXT -> "SHOT_ACTION";
            default -> "OTHER";
        };
    }

}
