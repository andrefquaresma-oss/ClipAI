package com.clipai.application.candidate;

import java.util.UUID;

public class CandidateClipBatchNotFoundException extends RuntimeException {
    public CandidateClipBatchNotFoundException(UUID mediaAssetId) {
        super("Clip generation has not been started for media asset: " + mediaAssetId);
    }
}
