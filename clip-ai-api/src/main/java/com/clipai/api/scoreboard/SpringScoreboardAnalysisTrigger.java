package com.clipai.api.scoreboard;

import com.clipai.application.scoreboard.ScoreboardAnalysisTrigger;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpringScoreboardAnalysisTrigger implements ScoreboardAnalysisTrigger {
    private final AsyncScoreboardAnalysisWorker worker;

    public SpringScoreboardAnalysisTrigger(AsyncScoreboardAnalysisWorker worker) {
        this.worker = worker;
    }

    @Override
    public void schedule(UUID analysisId) {
        worker.process(analysisId);
    }
}
