package com.clipai.api.candidate;

import com.clipai.domain.candidate.CandidateObservation;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.candidate.CandidateSignalType;

import java.time.Instant;
import java.util.UUID;

public record CandidateObservationResponse(
        UUID id,
        UUID detectionRunId,
        long timestampMs,
        CandidateSignalType type,
        FootballEventType eventType,
        double confidence,
        String evidence,
        Instant observedAt) {
    static CandidateObservationResponse from(CandidateObservation observation) {
        var signal = observation.signal();
        return new CandidateObservationResponse(observation.id(), observation.detectionRunId(),
                signal.timestampMs(), signal.type(),
                signal.eventType(), signal.confidence(), signal.evidence(), observation.observedAt());
    }
}
