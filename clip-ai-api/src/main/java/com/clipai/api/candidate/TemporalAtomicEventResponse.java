package com.clipai.api.candidate;

import com.clipai.application.candidate.TemporalAtomicEvent;

import java.util.List;
import java.util.UUID;

public record TemporalAtomicEventResponse(int ordinal, long timestampMs, String type,
                                          String sourceSignalType, double confidence,
                                          List<String> evidenceFamilies,
                                          List<UUID> sourceCandidateIds,
                                          boolean negativeOutcome, String evidence) {
    public TemporalAtomicEventResponse {
        evidenceFamilies = List.copyOf(evidenceFamilies);
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }

    static TemporalAtomicEventResponse from(TemporalAtomicEvent event) {
        return new TemporalAtomicEventResponse(event.ordinal(), event.timestampMs(), event.type().name(),
                event.sourceSignalType(), event.confidence(),
                event.evidenceFamilies().stream().map(Enum::name).toList(),
                event.sourceCandidateIds(), event.negativeOutcome(), event.evidence());
    }
}
