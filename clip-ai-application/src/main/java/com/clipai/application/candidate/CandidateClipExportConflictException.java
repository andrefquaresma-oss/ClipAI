package com.clipai.application.candidate;

public final class CandidateClipExportConflictException extends RuntimeException {
    public CandidateClipExportConflictException(String message) {
        super(message);
    }

    public CandidateClipExportConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
