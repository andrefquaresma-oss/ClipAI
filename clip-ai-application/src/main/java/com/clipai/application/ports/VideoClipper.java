package com.clipai.application.ports;

import java.nio.file.Path;

public interface VideoClipper {
    void cut(Path sourcePath, Path outputPath, long startTimeMs, long endTimeMs);

    void extractFrame(Path sourcePath, Path outputPath, long timestampMs);
}
