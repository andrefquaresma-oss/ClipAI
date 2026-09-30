package com.clipai.application.ports;

import java.net.URI;

public interface MediaDownloader {
    DownloadResult download(URI sourceUrl, String targetStorageKey);

    record DownloadResult(String storageKey, Long durationMs) {
    }
}
