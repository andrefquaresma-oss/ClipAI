package com.clipai.application.candidate;

import java.util.UUID;

public class CandidateEventNotFoundException extends RuntimeException {
    public CandidateEventNotFoundException(UUID eventId, UUID mediaAssetId) {
        super("Candidate event " + eventId + " was not found for media asset " + mediaAssetId);
    }
}
