package com.clipai.domain.candidate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateEvent(UUID id, UUID mediaAssetId, long startTimeMs, long endTimeMs,
                             long triggerTimestampMs, FootballEventType eventType, double score,
                             List<CandidateSignal> signals, String transcriptContext,
                             CandidateEventStatus status, Instant createdAt,
                             List<UUID> sourceCandidateIds, String mergeReason,
                             List<CandidateScoreComponent> scoreContributions, UUID detectionRunId) {
    public CandidateEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mediaAssetId, "mediaAssetId");
        if (startTimeMs < 0 || endTimeMs <= startTimeMs) {
            throw new IllegalArgumentException("candidate timestamps must be non-negative and end after start");
        }
        if (triggerTimestampMs < startTimeMs || triggerTimestampMs > endTimeMs) {
            throw new IllegalArgumentException("triggerTimestampMs " + triggerTimestampMs
                    + " must be within candidate window [" + startTimeMs + ", " + endTimeMs + "]");
        }
        Objects.requireNonNull(eventType, "eventType");
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("signals must not be empty");
        }
        if (signals.stream().anyMatch(signal ->
                signal.timestampMs() < startTimeMs || signal.timestampMs() > endTimeMs)) {
            throw new IllegalArgumentException("signal timestamps must be within the candidate window");
        }
        if (transcriptContext != null && transcriptContext.length() > 6000) {
            throw new IllegalArgumentException("transcriptContext must be at most 6000 characters");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        sourceCandidateIds = List.copyOf(Objects.requireNonNull(sourceCandidateIds, "sourceCandidateIds"));
        scoreContributions = List.copyOf(Objects.requireNonNull(scoreContributions, "scoreContributions"));
        if (sourceCandidateIds.isEmpty() || sourceCandidateIds.stream().anyMatch(Objects::isNull)
                || sourceCandidateIds.stream().distinct().count() != sourceCandidateIds.size()
                || !sourceCandidateIds.contains(id)) {
            throw new IllegalArgumentException("sourceCandidateIds must be unique and include the canonical id");
        }
        if (sourceCandidateIds.size() > 1 && (mergeReason == null || mergeReason.isBlank())) {
            throw new IllegalArgumentException("merged candidates must include a merge reason");
        }
        if (mergeReason != null && mergeReason.length() > 500) {
            throw new IllegalArgumentException("mergeReason must be at most 500 characters");
        }
    }

    public CandidateEvent(UUID id, UUID mediaAssetId, long startTimeMs, long endTimeMs,
                          long triggerTimestampMs, FootballEventType eventType, double score,
                          List<CandidateSignal> signals, String transcriptContext,
                          CandidateEventStatus status, Instant createdAt,
                          List<UUID> sourceCandidateIds, String mergeReason) {
        this(id, mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs, eventType, score,
                signals, transcriptContext, status, createdAt, sourceCandidateIds, mergeReason, List.of(), null);
    }

    public CandidateEvent(UUID id, UUID mediaAssetId, long startTimeMs, long endTimeMs,
                          long triggerTimestampMs, FootballEventType eventType, double score,
                          List<CandidateSignal> signals, String transcriptContext,
                          CandidateEventStatus status, Instant createdAt,
                          List<UUID> sourceCandidateIds, String mergeReason,
                          List<CandidateScoreComponent> scoreContributions) {
        this(id, mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs, eventType, score,
                signals, transcriptContext, status, createdAt, sourceCandidateIds, mergeReason,
                scoreContributions, null);
    }

    public CandidateEvent(UUID id, UUID mediaAssetId, long startTimeMs, long endTimeMs,
                          long triggerTimestampMs, FootballEventType eventType, double score,
                          List<CandidateSignal> signals, String transcriptContext,
                          CandidateEventStatus status, Instant createdAt) {
        this(id, mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs, eventType, score,
                signals, transcriptContext, status, createdAt, List.of(id), null, List.of(), null);
    }

    public static CandidateEvent detected(UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                          long triggerTimestampMs, FootballEventType eventType,
                                          double score, List<CandidateSignal> signals,
                                          String transcriptContext, Instant createdAt) {
        return detected(mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs, eventType,
                score, signals, transcriptContext, createdAt, List.of());
    }

    public static CandidateEvent detected(UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                          long triggerTimestampMs, FootballEventType eventType,
                                          double score, List<CandidateSignal> signals,
                                          String transcriptContext, Instant createdAt,
                                          List<CandidateScoreComponent> scoreContributions) {
        UUID id = UUID.randomUUID();
        return new CandidateEvent(id, mediaAssetId, startTimeMs, endTimeMs,
                triggerTimestampMs, eventType, score, signals, transcriptContext,
                CandidateEventStatus.DETECTED, createdAt, List.of(id), null, scoreContributions);
    }

    public static CandidateEvent rejected(UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                          long triggerTimestampMs, FootballEventType eventType,
                                          double score, List<CandidateSignal> signals,
                                          String transcriptContext, Instant createdAt) {
        return rejected(mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs, eventType,
                score, signals, transcriptContext, createdAt, List.of());
    }

    public static CandidateEvent rejected(UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                          long triggerTimestampMs, FootballEventType eventType,
                                          double score, List<CandidateSignal> signals,
                                          String transcriptContext, Instant createdAt,
                                          List<CandidateScoreComponent> scoreContributions) {
        UUID id = UUID.randomUUID();
        return new CandidateEvent(id, mediaAssetId, startTimeMs, endTimeMs,
                triggerTimestampMs, eventType, score, signals, transcriptContext,
                CandidateEventStatus.REJECTED, createdAt, List.of(id), null, scoreContributions);
    }

    public static CandidateEvent manual(UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                        long triggerTimestampMs, FootballEventType eventType,
                                        String note, Instant createdAt) {
        UUID id = UUID.randomUUID();
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.EVENT_RECONSTRUCTION,
                eventType, 1.0, triggerTimestampMs, "Manually annotated event");
        return new CandidateEvent(id, mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs,
                eventType, 1.0, List.of(signal), note, CandidateEventStatus.MANUAL, createdAt);
    }

    public CandidateEvent withCanonicalDetails(long canonicalStartTimeMs, long canonicalEndTimeMs,
                                               List<CandidateSignal> canonicalSignals,
                                               String canonicalContext, List<UUID> sources,
                                               String reason) {
        return new CandidateEvent(id, mediaAssetId, canonicalStartTimeMs, canonicalEndTimeMs,
                triggerTimestampMs, eventType, score, canonicalSignals, canonicalContext,
                status, createdAt, sources, reason, scoreContributions, detectionRunId);
    }

    public CandidateEvent withDetectionRunId(UUID runId) {
        return new CandidateEvent(id, mediaAssetId, startTimeMs, endTimeMs, triggerTimestampMs,
                eventType, score, signals, transcriptContext, status, createdAt,
                sourceCandidateIds, mergeReason, scoreContributions, runId);
    }

    public double liveEventProbability() {
        return probabilityFor(CandidateSignalType.LIVE_EVENT_CONTEXT);
    }

    public double replayProbability() {
        return Math.max(probabilityFor(CandidateSignalType.REPLAY_CONTEXT),
                probabilityFor(CandidateSignalType.RETROSPECTIVE_CONTEXT));
    }

    public List<String> rejectionReasons() {
        return signals.stream()
                .filter(signal -> signal.type() == CandidateSignalType.REJECTION_REASON)
                .map(CandidateSignal::evidence)
                .distinct()
                .toList();
    }

    private double probabilityFor(CandidateSignalType type) {
        return signals.stream()
                .filter(signal -> signal.type() == type)
                .mapToDouble(CandidateSignal::confidence)
                .max()
                .orElse(0);
    }
}
