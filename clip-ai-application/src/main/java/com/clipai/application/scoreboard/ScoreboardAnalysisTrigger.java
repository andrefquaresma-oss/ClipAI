package com.clipai.application.scoreboard;

import java.util.UUID;

public interface ScoreboardAnalysisTrigger {
    void schedule(UUID analysisId);
}
