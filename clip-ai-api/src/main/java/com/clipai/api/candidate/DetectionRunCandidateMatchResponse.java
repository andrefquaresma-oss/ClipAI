package com.clipai.api.candidate;

public record DetectionRunCandidateMatchResponse(CandidateEventResponse left,
                                                 CandidateEventResponse right,
                                                 String matchingReason,
                                                 boolean scoreChanged,
                                                 boolean eventTypeChanged,
                                                 boolean systemStatusChanged) {
}
