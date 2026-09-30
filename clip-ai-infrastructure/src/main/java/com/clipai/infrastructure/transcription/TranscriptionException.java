package com.clipai.infrastructure.transcription;

import com.clipai.application.media.ProcessingFailureException;

public final class TranscriptionException extends ProcessingFailureException {
    public TranscriptionException(String publicReason, String diagnostic, Throwable cause) {
        super(publicReason, diagnostic, cause);
    }

    public TranscriptionException(String publicReason, String diagnostic) {
        super(publicReason, diagnostic);
    }
}
