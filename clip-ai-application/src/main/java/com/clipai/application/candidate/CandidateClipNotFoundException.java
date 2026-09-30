package com.clipai.application.candidate;

import java.util.UUID;

public final class CandidateClipNotFoundException extends RuntimeException {
    public CandidateClipNotFoundException(UUID candidateId) {
        super("Clip has not been exported for candidate " + candidateId);
    }
}
