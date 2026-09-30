package com.clipai.api.media;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

public record VideoUploadRequest(
        @Schema(type = "string", format = "binary", description = "Video file to upload")
        MultipartFile file) {
}
