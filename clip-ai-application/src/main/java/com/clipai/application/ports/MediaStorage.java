package com.clipai.application.ports;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface MediaStorage {
    String store(UUID mediaAssetId, String extension, InputStream content);

    Path resolve(String storageKey);

    Path audioPath(String sourceStorageKey);

    void delete(String storageKey);
}
