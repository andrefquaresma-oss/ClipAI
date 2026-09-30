package com.clipai.application.candidate;

import java.util.UUID;

public class CandidateDetectionConflictException extends RuntimeException {
    public CandidateDetectionConflictException(UUID mediaAssetId, String message) {
        super(message + ": " + mediaAssetId);
    }
}
