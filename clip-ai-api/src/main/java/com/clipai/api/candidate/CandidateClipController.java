package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipService;
import com.clipai.application.candidate.GoalReviewAssetService;
import com.clipai.application.ports.MediaStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.io.IOException;

@RestController
@Validated
@RequestMapping("/api/media-assets/{mediaAssetId}/candidates/{candidateId}/clip")
@Tag(name = "Football clips", description = "Export and download reviewed goal, card, and shot candidates")
public class CandidateClipController {
    private final CandidateClipService clips;
    private final GoalReviewAssetService reviewAssets;
    private final MediaStorage mediaStorage;

    public CandidateClipController(CandidateClipService clips, GoalReviewAssetService reviewAssets,
                                   MediaStorage mediaStorage) {
        this.clips = clips;
        this.reviewAssets = reviewAssets;
        this.mediaStorage = mediaStorage;
    }

    @PostMapping
    @Operation(summary = "Export a candidate clip",
            description = "Cuts this candidate's timestamp window and stores it under the goals, cards, shots, "
                    + "or other folder using the candidate's persisted start and end times unchanged. Goal "
                    + "candidates already include their calculated pre-roll; this endpoint does not add another. "
                    + "It also stores pre-goal stills separately under the asset's analysis directory and can "
                    + "extract transcript-cued replay clips. "
                    + "Candidate detections are hypotheses; review the transcript and evidence before exporting.")
    @ApiResponse(responseCode = "201", description = "Clip exported or an existing clip returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CandidateClipResponse.class)))
    public ResponseEntity<CandidateClipResponse> export(@PathVariable UUID mediaAssetId,
                                                        @PathVariable UUID candidateId,
                                                        @RequestParam(defaultValue = "false") boolean regenerate) {
        var clip = clips.exportWithResult(mediaAssetId, candidateId, regenerate).clip();
        String downloadUrl = "/api/media-assets/" + mediaAssetId + "/candidates/" + candidateId + "/clip";
        GoalReviewAssetsResponse review = clip.eventType() == com.clipai.domain.candidate.FootballEventType.GOAL
                ? GoalReviewAssetsResponse.from(mediaAssetId, candidateId,
                        reviewAssets.export(mediaAssetId, candidateId))
                : GoalReviewAssetsResponse.empty();
        return ResponseEntity.created(URI.create(downloadUrl))
                .body(CandidateClipResponse.from(clip, downloadUrl, review));
    }

    @GetMapping(produces = "video/mp4")
    @Operation(summary = "Download an exported clip")
    public ResponseEntity<?> download(@PathVariable UUID mediaAssetId, @PathVariable UUID candidateId,
                                      @RequestParam(defaultValue = "false") boolean inline,
                                      @RequestHeader HttpHeaders headers) throws IOException {
        var clip = clips.findExported(mediaAssetId, candidateId);
        Resource resource = new FileSystemResource(mediaStorage.resolve(clip.storageKey()));
        if (!inline) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(candidateId + ".mp4").build().toString())
                    .body(resource);
        }
        return com.clipai.api.media.MediaRangeResponses.stream(resource, candidateId + ".mp4",
                MediaType.valueOf("video/mp4"), headers);
    }

    @GetMapping(value = "/review-assets", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List goal build-up frames and replay clips")
    public GoalReviewAssetsResponse reviewAssets(@PathVariable UUID mediaAssetId,
                                                 @PathVariable UUID candidateId) {
        return GoalReviewAssetsResponse.from(mediaAssetId, candidateId,
                reviewAssets.find(mediaAssetId, candidateId));
    }

    @GetMapping(value = "/frames/{frameNumber}", produces = "image/jpeg")
    @Operation(summary = "Download a pre-goal build-up frame")
    public ResponseEntity<Resource> downloadFrame(@PathVariable UUID mediaAssetId,
                                                   @PathVariable UUID candidateId,
                                                   @PathVariable @Min(1) @Max(12) int frameNumber) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(reviewAssets.findFrame(mediaAssetId, candidateId, frameNumber)));
    }

    @GetMapping(value = "/replays/{replayNumber}", produces = "video/mp4")
    @Operation(summary = "Download an exported goal replay clip")
    public ResponseEntity<Resource> downloadReplay(@PathVariable UUID mediaAssetId,
                                                   @PathVariable UUID candidateId,
                                                   @PathVariable @Min(1) @Max(5) int replayNumber) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(new FileSystemResource(reviewAssets.findReplay(mediaAssetId, candidateId, replayNumber)));
    }
}
