package com.clipai.infrastructure.process;

import com.clipai.application.media.ProcessingFailureException;

public final class MediaProcessingException extends ProcessingFailureException {
    public MediaProcessingException(String publicReason, String diagnostic) {
        super(publicReason, diagnostic);
    }

    public MediaProcessingException(String publicReason, String diagnostic, Throwable cause) {
        super(publicReason, diagnostic, cause);
    }
}
