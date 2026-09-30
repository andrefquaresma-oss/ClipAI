package com.clipai.application.ports;

import java.nio.file.Path;
import java.util.Objects;

public record ClipStorageLocation(String storageKey, Path path) {
    public ClipStorageLocation {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        Objects.requireNonNull(path, "path");
    }
}
