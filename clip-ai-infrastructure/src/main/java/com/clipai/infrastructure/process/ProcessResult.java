package com.clipai.infrastructure.process;

public record ProcessResult(int exitCode, String stdout, String stderr, long durationMs) {
}
