package com.clipai.application.ports;

import java.nio.file.Path;

public interface MediaProcessor {
    ProcessingResult process(Path mediaPath, Path audioPath);

    record ProcessingResult(Path outputPath) {
    }
}
