package com.clipai.api.media;

import com.clipai.domain.media.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterMediaAssetRequest(
        @NotBlank @Size(max = 100) String source,
        @NotBlank @Size(max = 2048) @Pattern(regexp = "(?i)^https?://[^\\s]+$") String sourceUrl,
        @NotBlank @Size(max = 500) String title,
        @NotNull ContentType contentType) {
}
