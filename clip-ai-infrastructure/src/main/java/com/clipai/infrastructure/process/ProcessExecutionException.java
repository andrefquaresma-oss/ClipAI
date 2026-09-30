package com.clipai.infrastructure.process;

public final class ProcessExecutionException extends RuntimeException {
    public ProcessExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProcessExecutionException(String message) {
        super(message);
    }
}
