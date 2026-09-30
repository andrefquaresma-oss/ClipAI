package com.clipai.application.scoreboard;

import java.nio.file.Path;
import java.util.UUID;

public interface ScoreboardOcrService {
    ScoreboardOcrResult analyze(Path videoPath, UUID analysisId);
}
