package com.clipai.api.media;

import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetPart;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaAssetResponseTest {
    @Test
    void mapsDomainValuesWithoutPersistenceTypes() {
        var asset = MediaAsset.register("source", "https://example.com/watch/1", "Title",
                ContentType.NEWS, Instant.parse("2026-01-01T00:00:00Z"));

        MediaAssetResponse response = MediaAssetResponse.from(asset);

        assertEquals(asset.getId(), response.id());
        assertEquals(asset.getStatus(), response.status());
        assertEquals(asset.getSourceUrl(), response.sourceUrl());
        assertEquals(MediaAssetPart.OTHER, response.matchPart());
    }
}
