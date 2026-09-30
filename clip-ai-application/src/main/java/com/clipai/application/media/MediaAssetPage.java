package com.clipai.application.media;

import com.clipai.domain.media.MediaAsset;

import java.util.List;

public record MediaAssetPage(List<MediaAsset> items, int page, int size, long totalElements) {
    public MediaAssetPage {
        items = List.copyOf(items);
    }
}
