package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CandidateClipCategoryTest {
    @Test
    void groupsGoalsCardsAndShotsIntoDedicatedFolders() {
        assertEquals(CandidateClipCategory.GOALS, CandidateClipCategory.forEventType(FootballEventType.GOAL));
        assertEquals(CandidateClipCategory.CARDS,
                CandidateClipCategory.forEventType(FootballEventType.YELLOW_CARD));
        assertEquals(CandidateClipCategory.CARDS,
                CandidateClipCategory.forEventType(FootballEventType.RED_CARD));
        assertEquals(CandidateClipCategory.SHOTS, CandidateClipCategory.forEventType(FootballEventType.SHOT));
    }

    @Test
    void groupsOtherDetectedCandidateTypesIntoTheOtherFolder() {
        assertEquals(CandidateClipCategory.OTHER,
                CandidateClipCategory.forEventType(FootballEventType.PENALTY));
        assertEquals(CandidateClipCategory.OTHER,
                CandidateClipCategory.forEventType(FootballEventType.UNKNOWN));
    }
}
