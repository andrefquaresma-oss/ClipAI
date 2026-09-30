package com.clipai.application.candidate;

import java.util.UUID;

public interface CandidateDetectionTrigger {
    void schedule(UUID detectionRunId);
}
