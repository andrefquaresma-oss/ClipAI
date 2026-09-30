package com.clipai.application.media;

public final class InvalidVideoUploadException extends RuntimeException {
    public InvalidVideoUploadException(String message) {
        super(message);
    }
}
