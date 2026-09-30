package com.clipai.application.media;

import com.clipai.domain.media.ContentType;

public record RegisterMediaAssetCommand(String source, String sourceUrl, String title, ContentType contentType) {
}
