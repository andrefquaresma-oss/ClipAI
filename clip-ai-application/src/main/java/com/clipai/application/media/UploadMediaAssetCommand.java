package com.clipai.application.media;

import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAssetPart;

import java.time.LocalDate;

public record UploadMediaAssetCommand(String originalFilename, long fileSize, String source,
                                      String title, ContentType contentType, String competition,
                                      String homeTeam, String awayTeam, LocalDate matchDate,
                                      String language, MediaAssetPart matchPart, UploadContent content) {
    public UploadMediaAssetCommand(String originalFilename, long fileSize, String source,
                                   String title, ContentType contentType, String competition,
                                   String homeTeam, String awayTeam, LocalDate matchDate,
                                   String language, UploadContent content) {
        this(originalFilename, fileSize, source, title, contentType, competition, homeTeam, awayTeam,
                matchDate, language, MediaAssetPart.OTHER, content);
    }

    public UploadMediaAssetCommand(String originalFilename, long fileSize, String source,
                                   String title, ContentType contentType, UploadContent content) {
        this(originalFilename, fileSize, source, title, contentType, null, null, null,
                null, null, MediaAssetPart.OTHER, content);
    }
}
