package com.clipai.api.media;

import com.clipai.application.media.MediaAssetApplicationService;
import com.clipai.application.ports.MediaStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/source")
@Tag(name = "Media assets", description = "Stream stored source media for review")
public class MediaSourceController {
    private final MediaAssetApplicationService mediaAssets;
    private final MediaStorage storage;

    public MediaSourceController(MediaAssetApplicationService mediaAssets, MediaStorage storage) {
        this.mediaAssets = mediaAssets;
        this.storage = storage;
    }

    @GetMapping
    @Operation(summary = "Stream the stored source media for in-browser review")
    public ResponseEntity<?> stream(@PathVariable UUID mediaAssetId,
                                    @RequestHeader HttpHeaders headers) throws IOException {
        var asset = mediaAssets.get(mediaAssetId);
        if (asset.getLocalStoragePath() == null || asset.getLocalStoragePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        var path = storage.resolve(asset.getLocalStoragePath());
        String contentType = Files.probeContentType(path);
        MediaType mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        return MediaRangeResponses.stream(new FileSystemResource(path), "source-video", mediaType, headers);
    }
}
