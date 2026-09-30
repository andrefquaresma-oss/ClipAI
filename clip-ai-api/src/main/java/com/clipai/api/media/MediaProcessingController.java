package com.clipai.api.media;

import com.clipai.application.media.MediaProcessingControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{id}/processing")
@Tag(name = "Media processing", description = "Start and monitor persisted processing stages")
public class MediaProcessingController {
    private final MediaProcessingControlService service;

    public MediaProcessingController(MediaProcessingControlService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get processing status")
    public MediaProcessingStatusResponse status(@PathVariable UUID id) {
        return MediaProcessingStatusResponse.from(service.getStatus(id));
    }

    @PostMapping
    @Operation(summary = "Start the complete processing pipeline")
    public ResponseEntity<MediaProcessingStatusResponse> start(@PathVariable UUID id) {
        service.start(id);
        return ResponseEntity.accepted().location(URI.create("/api/media-assets/" + id + "/processing"))
                .body(MediaProcessingStatusResponse.from(service.getStatus(id)));
    }

    @PostMapping("/audio-extraction")
    @Operation(summary = "Start audio extraction")
    public ResponseEntity<MediaProcessingStatusResponse> extractAudio(@PathVariable UUID id) {
        service.startAudioExtraction(id);
        return ResponseEntity.accepted().body(MediaProcessingStatusResponse.from(service.getStatus(id)));
    }

    @PostMapping("/transcription")
    @Operation(summary = "Start transcription")
    public ResponseEntity<MediaProcessingStatusResponse> transcribe(@PathVariable UUID id) {
        service.startTranscription(id);
        return ResponseEntity.accepted().body(MediaProcessingStatusResponse.from(service.getStatus(id)));
    }

    @PostMapping("/retry")
    @Operation(summary = "Retry failed processing")
    public ResponseEntity<MediaProcessingStatusResponse> retry(@PathVariable UUID id) {
        service.retry(id);
        return ResponseEntity.accepted().body(MediaProcessingStatusResponse.from(service.getStatus(id)));
    }
}
