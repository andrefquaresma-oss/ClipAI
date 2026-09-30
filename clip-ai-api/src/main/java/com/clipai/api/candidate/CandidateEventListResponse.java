package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateEventList;
import com.clipai.domain.candidate.CandidateDetectionStatus;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import com.clipai.application.matchcontext.MatchPhase;
import com.clipai.domain.candidate.CandidateEvent;

public record CandidateEventListResponse(UUID mediaAssetId, CandidateDetectionStatus detectionStatus,
                                         String failureReason, List<CandidateEventResponse> events) {
    public CandidateEventListResponse {
        events = List.copyOf(events);
    }

    static CandidateEventListResponse from(CandidateEventList result) {
        return from(result, ignored -> MatchPhase.UNKNOWN);
    }

    static CandidateEventListResponse from(CandidateEventList result,
                                           Function<CandidateEvent, MatchPhase> phaseForEvent) {
        return new CandidateEventListResponse(result.mediaAssetId(), result.detectionStatus(),
                result.failureReason(), result.events().stream()
                        .map(event -> CandidateEventResponse.from(event, phaseForEvent.apply(event))).toList());
    }
}
