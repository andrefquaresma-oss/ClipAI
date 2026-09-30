package com.clipai.application.candidate;

public record TemporalEventRelationship(int fromOrdinal, int toOrdinal,
                                        TemporalRelationshipType type, String explanation) {
}
