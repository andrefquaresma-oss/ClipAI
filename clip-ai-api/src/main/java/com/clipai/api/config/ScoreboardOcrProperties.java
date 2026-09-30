package com.clipai.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.scoreboard-ocr")
public record ScoreboardOcrProperties(String url, Duration timeout, boolean enabled, String modelVersion) {
    public ScoreboardOcrProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:8000";
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(30);
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            modelVersion = "PaddleOCR-3.7.0-PP-OCRv6";
        }
    }
}
