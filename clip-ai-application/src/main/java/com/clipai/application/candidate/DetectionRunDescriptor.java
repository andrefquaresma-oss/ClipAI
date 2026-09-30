package com.clipai.application.candidate;

public record DetectionRunDescriptor(String detectorVersion, String configurationHash) {
    public DetectionRunDescriptor {
        if (detectorVersion == null || detectorVersion.isBlank()) {
            throw new IllegalArgumentException("detectorVersion must not be blank");
        }
        if (configurationHash == null || configurationHash.isBlank()) {
            throw new IllegalArgumentException("configurationHash must not be blank");
        }
    }
}
