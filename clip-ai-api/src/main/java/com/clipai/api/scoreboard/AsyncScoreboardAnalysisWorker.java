package com.clipai.api.scoreboard;

import com.clipai.application.scoreboard.ScoreboardAnalysisProcessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AsyncScoreboardAnalysisWorker {
    private final ScoreboardAnalysisProcessor processor;

    public AsyncScoreboardAnalysisWorker(ScoreboardAnalysisProcessor processor) {
        this.processor = processor;
    }

    @Async("mediaProcessingExecutor")
    public void process(UUID analysisId) {
        processor.process(analysisId);
    }
}
