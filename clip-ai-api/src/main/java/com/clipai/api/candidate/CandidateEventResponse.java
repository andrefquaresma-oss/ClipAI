package com.clipai.api.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.application.candidate.TemporalEventSequence;
import com.clipai.application.matchcontext.MatchPhase;
import com.clipai.domain.candidate.CandidateScoreComponent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CandidateEventResponse(UUID id, UUID mediaAssetId, long startTimeMs, long endTimeMs,
                                     long triggerTimestampMs, FootballEventType eventType, double score,
                                     List<CandidateSignalResponse> signals, String transcriptContext,
                                     CandidateEventStatus status, Instant createdAt,
                                     double liveEventProbability, double replayProbability,
                                     List<String> rejectionReasons, List<UUID> sourceCandidateIds,
                                     List<UUID> sourceObservationIds,
                                     String mergeReason, TemporalEventSequenceResponse temporalSequence,
                                     List<CandidateScoreComponent> scoreContributions,
                                     MatchPhase matchPhase, UUID detectionRunId) {
    public CandidateEventResponse {
        signals = List.copyOf(signals);
        rejectionReasons = List.copyOf(rejectionReasons);
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
        sourceObservationIds = List.copyOf(sourceObservationIds);
        scoreContributions = List.copyOf(scoreContributions);
    }

    static CandidateEventResponse from(CandidateEvent event) {
        return from(event, MatchPhase.UNKNOWN);
    }

    static CandidateEventResponse from(CandidateEvent event, MatchPhase matchPhase) {
        return from(event, matchPhase, List.of());
    }

    static CandidateEventResponse from(CandidateEvent event, MatchPhase matchPhase,
                                       List<UUID> sourceObservationIds) {
        return new CandidateEventResponse(event.id(), event.mediaAssetId(), event.startTimeMs(),
                event.endTimeMs(), event.triggerTimestampMs(), event.eventType(), event.score(),
                event.signals().stream().map(CandidateSignalResponse::from).toList(),
                event.transcriptContext(), event.status(), event.createdAt(),
                event.liveEventProbability(), event.replayProbability(), event.rejectionReasons(),
                event.sourceCandidateIds(), sourceObservationIds, event.mergeReason(),
                TemporalEventSequenceResponse.from(TemporalEventSequence.from(event)),
                event.scoreContributions(), matchPhase, event.detectionRunId());
    }
}
