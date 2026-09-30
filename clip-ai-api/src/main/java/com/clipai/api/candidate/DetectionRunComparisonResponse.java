package com.clipai.api.candidate;

import com.clipai.application.candidate.DetectionRunComparison;
import com.clipai.application.matchcontext.MatchPhase;

import java.util.List;
import java.util.Map;

public record DetectionRunComparisonResponse(DetectionRunResponse leftRun,
                                             DetectionRunResponse rightRun,
                                             List<DetectionRunCandidateMatchResponse> matchingCandidates,
                                             List<CandidateEventResponse> onlyInLeft,
                                             List<CandidateEventResponse> onlyInRight,
                                             ObservationComparison observations) {
    public DetectionRunComparisonResponse {
        matchingCandidates = List.copyOf(matchingCandidates);
        onlyInLeft = List.copyOf(onlyInLeft);
        onlyInRight = List.copyOf(onlyInRight);
    }

    static DetectionRunComparisonResponse from(DetectionRunComparison comparison) {
        return from(comparison, ignored -> MatchPhase.UNKNOWN);
    }

    static DetectionRunComparisonResponse from(
            DetectionRunComparison comparison,
            java.util.function.Function<com.clipai.domain.candidate.CandidateEvent, MatchPhase> phaseFor) {
        List<DetectionRunCandidateMatchResponse> matches = comparison.matchingCandidates().stream()
                .map(match -> new DetectionRunCandidateMatchResponse(
                        CandidateEventResponse.from(match.left(), phaseFor.apply(match.left())),
                        CandidateEventResponse.from(match.right(), phaseFor.apply(match.right())),
                        match.matchingReason(), match.scoreChanged(), match.eventTypeChanged(),
                        match.systemStatusChanged()))
                .toList();
        List<CandidateEventResponse> onlyLeft = comparison.onlyInLeft().stream()
                .map(event -> CandidateEventResponse.from(event, phaseFor.apply(event))).toList();
        List<CandidateEventResponse> onlyRight = comparison.onlyInRight().stream()
                .map(event -> CandidateEventResponse.from(event, phaseFor.apply(event))).toList();
        ObservationComparison observations = comparison.observations() == null ? null
                : new ObservationComparison(comparison.observations().centerTimestampMs(),
                        comparison.observations().windowMs(), comparison.observations().left().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                entry -> entry.getValue().stream().map(CandidateObservationResponse::from).toList())),
                        comparison.observations().right().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                        entry -> entry.getValue().stream()
                                                .map(CandidateObservationResponse::from).toList())));
        return new DetectionRunComparisonResponse(DetectionRunResponse.from(comparison.leftRun()),
                DetectionRunResponse.from(comparison.rightRun()), matches, onlyLeft, onlyRight, observations);
    }

    public record ObservationComparison(long centerTimestampMs, long windowMs,
                                        Map<String, List<CandidateObservationResponse>> left,
                                        Map<String, List<CandidateObservationResponse>> right) {
    }
}
