package com.clipai.api.candidate;

import com.clipai.domain.candidate.FootballEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootballEventTypeControllerTest {
    @Test
    void exposesTheBackendEventTaxonomyWithReadableLabels() {
        var eventTypes = new FootballEventTypeController().list();

        assertEquals(FootballEventType.values().length, eventTypes.size());
        assertTrue(eventTypes.stream().anyMatch(type ->
                type.code().equals("GOAL_DISALLOWED") && type.label().equals("GOAL DISALLOWED")));
        assertTrue(eventTypes.stream().anyMatch(type -> type.code().equals("UNKNOWN")));
    }
}
