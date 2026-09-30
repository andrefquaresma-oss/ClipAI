package com.clipai.api.candidate;

import com.clipai.application.candidate.TemporalEventRelationship;

public record TemporalEventRelationshipResponse(int fromOrdinal, int toOrdinal,
                                                String type, String explanation) {
    static TemporalEventRelationshipResponse from(TemporalEventRelationship relationship) {
        return new TemporalEventRelationshipResponse(relationship.fromOrdinal(), relationship.toOrdinal(),
                relationship.type().name(), relationship.explanation());
    }
}
