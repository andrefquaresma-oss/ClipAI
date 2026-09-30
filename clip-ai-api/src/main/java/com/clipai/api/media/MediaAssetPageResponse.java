package com.clipai.api.media;

import java.util.List;

public record MediaAssetPageResponse(List<MediaAssetResponse> items, int page, int size,
                                    long totalElements, int totalPages) {
    public MediaAssetPageResponse {
        items = List.copyOf(items);
    }
}
