package com.clipai.application.candidate;

public final class DetectionRunNotFoundException extends RuntimeException {
    public DetectionRunNotFoundException(String runId) {
        super("Detection run not found: " + runId);
    }
}
