package com.clipai.application.candidate;

import java.util.UUID;

public class CandidateClipBatchSchedulingException extends RuntimeException {
    public CandidateClipBatchSchedulingException(UUID mediaAssetId, Throwable cause) {
        super("Clip generation could not be scheduled for media asset: " + mediaAssetId, cause);
    }
}
