package com.clipai.application.candidate;

import com.clipai.domain.candidate.FootballEventType;

public enum CandidateClipCategory {
    GOALS("goals"),
    CARDS("cards"),
    SHOTS("shots"),
    OTHER("other");

    private final String folderName;

    CandidateClipCategory(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }

    public static CandidateClipCategory forEventType(FootballEventType eventType) {
        return switch (eventType) {
            case GOAL -> GOALS;
            case YELLOW_CARD, RED_CARD -> CARDS;
            case SHOT -> SHOTS;
            default -> OTHER;
        };
    }
}
