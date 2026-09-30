package com.clipai.api.candidate;

import com.clipai.application.candidate.TemporalEventSequence;

import java.util.List;

public record TemporalEventSequenceResponse(String assessment,
                                            List<TemporalAtomicEventResponse> events,
                                            List<TemporalEventRelationshipResponse> relationships,
                                            List<String> negativeEvidence) {
    public TemporalEventSequenceResponse {
        events = List.copyOf(events);
        relationships = List.copyOf(relationships);
        negativeEvidence = List.copyOf(negativeEvidence);
    }

    static TemporalEventSequenceResponse from(TemporalEventSequence sequence) {
        return new TemporalEventSequenceResponse(sequence.assessment(),
                sequence.events().stream().map(TemporalAtomicEventResponse::from).toList(),
                sequence.relationships().stream().map(TemporalEventRelationshipResponse::from).toList(),
                sequence.negativeEvidence());
    }
}
