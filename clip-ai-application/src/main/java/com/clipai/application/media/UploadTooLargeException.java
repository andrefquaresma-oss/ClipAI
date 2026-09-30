package com.clipai.application.media;

public final class UploadTooLargeException extends RuntimeException {
    public UploadTooLargeException() {
        super("Uploaded file exceeds the configured maximum size");
    }
}
