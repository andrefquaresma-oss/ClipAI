package com.clipai.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.transcription")
public record TranscriptionProperties(String url, Duration timeout) {
}
