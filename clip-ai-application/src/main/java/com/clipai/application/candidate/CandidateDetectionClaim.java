package com.clipai.application.candidate;

import java.time.Instant;
import java.util.UUID;

public interface CandidateDetectionClaim {
    boolean claim(UUID mediaAssetId, Instant now);
}
