package com.clipai.application.ports;

import java.nio.file.Path;

public interface AudioExtractor {
    Path extractAudio(Path mediaPath, Path audioPath);
}
