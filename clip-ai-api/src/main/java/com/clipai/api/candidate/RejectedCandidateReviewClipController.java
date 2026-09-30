package com.clipai.api.candidate;

import com.clipai.application.candidate.RejectedCandidateReviewClipService;
import com.clipai.application.candidate.RejectedReviewClipStatus;
import com.clipai.application.ports.MediaStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/candidates/{candidateId}/rejected-review-clip")
@Tag(name = "Rejected candidate review", description = "Lazily generate and stream temporary short review clips")
public class RejectedCandidateReviewClipController {
    private final RejectedCandidateReviewClipService clips;
    private final MediaStorage mediaStorage;

    public RejectedCandidateReviewClipController(RejectedCandidateReviewClipService clips,
                                                 MediaStorage mediaStorage) {
        this.clips = clips;
        this.mediaStorage = mediaStorage;
    }

    @GetMapping
    @Operation(summary = "Get rejected-candidate review clip status")
    public RejectedReviewClipResponse get(@PathVariable UUID mediaAssetId,
                                          @PathVariable UUID candidateId) {
        var result = clips.get(mediaAssetId, candidateId);
        return response(mediaAssetId, candidateId, result);
    }

    @PostMapping
    @Operation(summary = "Lazily generate a short rejected-candidate review clip")
    public ResponseEntity<RejectedReviewClipResponse> generate(@PathVariable UUID mediaAssetId,
                                                                @PathVariable UUID candidateId) {
        var result = clips.request(mediaAssetId, candidateId);
        var response = response(mediaAssetId, candidateId, result);
        if (result.status() == RejectedReviewClipStatus.READY) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping(value = "/video", produces = "video/mp4")
    @Operation(summary = "Stream a generated rejected-candidate review clip")
    public ResponseEntity<?> stream(@PathVariable UUID mediaAssetId,
                                    @PathVariable UUID candidateId,
                                    @RequestHeader HttpHeaders headers) throws IOException {
        var location = clips.find(mediaAssetId, candidateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Rejected-candidate review clip is not ready"));
        Resource resource = new FileSystemResource(mediaStorage.resolve(location.storageKey()));
        return com.clipai.api.media.MediaRangeResponses.stream(resource, candidateId + "-review.mp4",
                MediaType.valueOf("video/mp4"), headers);
    }

    private RejectedReviewClipResponse response(UUID mediaAssetId, UUID candidateId,
            com.clipai.application.candidate.RejectedReviewClipResult result) {
        String videoUrl = result.status() == RejectedReviewClipStatus.READY
                ? "/api/media-assets/" + mediaAssetId + "/candidates/" + candidateId
                    + "/rejected-review-clip/video" : null;
        return RejectedReviewClipResponse.from(candidateId, result, videoUrl);
    }
}
