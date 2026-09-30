package com.clipai.application.candidate;

import java.util.List;
import java.util.UUID;

public record TemporalAtomicEvent(int ordinal, long timestampMs, TemporalAtomicEventType type,
                                  String sourceSignalType, double confidence,
                                  List<TemporalEvidenceFamily> evidenceFamilies,
                                  List<UUID> sourceCandidateIds,
                                  boolean negativeOutcome, String evidence) {
    public TemporalAtomicEvent {
        evidenceFamilies = List.copyOf(evidenceFamilies);
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
    }
}
