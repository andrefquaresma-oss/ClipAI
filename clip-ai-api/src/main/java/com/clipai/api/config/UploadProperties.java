package com.clipai.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "clip-ai.upload")
public record UploadProperties(DataSize maxFileSize, DataSize maxRequestSize) {
}
