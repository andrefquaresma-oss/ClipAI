package com.clipai.application.media;

public class ProcessingFailureException extends RuntimeException {
    private final String publicReason;

    public ProcessingFailureException(String publicReason, String diagnostic, Throwable cause) {
        super(diagnostic, cause);
        this.publicReason = publicReason;
    }

    public ProcessingFailureException(String publicReason, String diagnostic) {
        super(diagnostic);
        this.publicReason = publicReason;
    }

    public String publicReason() {
        return publicReason;
    }
}
