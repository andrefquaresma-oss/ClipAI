package com.clipai.application.candidate;

import java.util.UUID;

public class CandidateDetectionSchedulingException extends RuntimeException {
    public CandidateDetectionSchedulingException(UUID mediaAssetId, Throwable cause) {
        super("Unable to schedule candidate detection for media asset " + mediaAssetId, cause);
    }
}
