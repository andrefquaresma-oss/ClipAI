package com.clipai.api.media;

import com.clipai.application.media.MediaAssetApplicationService;
import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.RegisterMediaAssetCommand;
import com.clipai.application.media.UploadMediaAssetCommand;
import com.clipai.application.media.UploadMediaAssetService;
import com.clipai.application.transcript.TranscriptNotFoundException;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.media.MediaAssetPart;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/media-assets")
@Tag(name = "Media assets", description = "Register and inspect VOD metadata")
public class MediaAssetController {
    private static final Logger log = LoggerFactory.getLogger(MediaAssetController.class);
    private final MediaAssetApplicationService service;
    private final UploadMediaAssetService uploadService;
    private final TranscriptRepository transcripts;

    public MediaAssetController(MediaAssetApplicationService service, UploadMediaAssetService uploadService,
                                TranscriptRepository transcripts) {
        this.service = service;
        this.uploadService = uploadService;
        this.transcripts = transcripts;
    }

    @PostMapping
    @Operation(summary = "Register media metadata", description = "Registers metadata only; it does not download media.")
    public ResponseEntity<MediaAssetResponse> register(@Valid @RequestBody RegisterMediaAssetRequest request) {
        var asset = service.register(new RegisterMediaAssetCommand(request.source(), request.sourceUrl(),
                request.title(), request.contentType()));
        log.info("Registered media asset mediaAssetId={} status={}", asset.getId(), asset.getStatus());
        return ResponseEntity.created(URI.create("/api/media-assets/" + asset.getId()))
                .body(MediaAssetResponse.from(asset));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and process a video",
            description = "Stores an uploaded video and schedules audio extraction and transcription asynchronously.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = VideoUploadRequest.class))))
    @ApiResponse(responseCode = "202", description = "Upload accepted for asynchronous processing",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = UploadMediaAssetResponse.class)))
    public ResponseEntity<UploadMediaAssetResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) ContentType contentType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String competition,
            @RequestParam(required = false) String homeTeam,
            @RequestParam(required = false) String awayTeam,
            @RequestParam(required = false) LocalDate matchDate,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) MediaAssetPart matchPart,
            @RequestParam(defaultValue = "true") boolean processImmediately) {
        var asset = uploadService.upload(new UploadMediaAssetCommand(file.getOriginalFilename(), file.getSize(),
                source, title, contentType, competition, homeTeam, awayTeam, matchDate, language, matchPart,
                file::getInputStream), processImmediately);
        log.info("Video upload accepted mediaAssetId={} status={}", asset.getId(), asset.getStatus());
        return ResponseEntity.accepted()
                .location(URI.create("/api/media-assets/" + asset.getId()))
                .body(UploadMediaAssetResponse.from(asset));
    }

    @GetMapping
    @Operation(summary = "List media assets", description = "Returns a page of metadata, optionally filtered by status.")
    public MediaAssetPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) MediaAssetStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        boolean descending = switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw new IllegalArgumentException("direction must be asc or desc");
        };
        MediaAssetPage result = service.list(page, size, status, search, sort, descending);
        int totalPages = result.totalElements() == 0 ? 0
                : (int) Math.ceil((double) result.totalElements() / result.size());
        return new MediaAssetPageResponse(result.items().stream().map(MediaAssetResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), totalPages);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a media asset")
    public MediaAssetResponse get(@PathVariable UUID id) {
        var asset = service.get(id);
        return MediaAssetResponse.from(asset, transcripts.findByMediaAssetId(id).orElse(null));
    }

    @GetMapping("/{id}/transcript")
    @Operation(summary = "Get transcript segments")
    public TranscriptResponse getTranscript(@PathVariable UUID id) {
        service.get(id);
        return TranscriptResponse.from(transcripts.findByMediaAssetId(id)
                .orElseThrow(() -> new TranscriptNotFoundException(id)));
    }
}
