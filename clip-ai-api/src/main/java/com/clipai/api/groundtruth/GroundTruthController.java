package com.clipai.api.groundtruth;

import com.clipai.application.groundtruth.GroundTruthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/ground-truth")
@Tag(name = "Ground truth", description = "Persist human-annotated events separately from detections")
public class GroundTruthController {
    private final GroundTruthService service;

    public GroundTruthController(GroundTruthService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get ground-truth events and evaluation sufficiency")
    public GroundTruthMatchResponse get(@PathVariable UUID mediaAssetId) {
        return GroundTruthMatchResponse.from(service.get(mediaAssetId));
    }

    @PutMapping
    @Operation(summary = "Set match annotation review status")
    public GroundTruthMatchResponse setReview(@PathVariable UUID mediaAssetId,
                                              @Valid @RequestBody GroundTruthReviewRequest request) {
        return GroundTruthMatchResponse.from(service.setStatus(mediaAssetId, request.status()));
    }

    @PostMapping("/events")
    @Operation(summary = "Create an independent ground-truth event")
    public ResponseEntity<GroundTruthEventResponse> create(@PathVariable UUID mediaAssetId,
                                                           @Valid @RequestBody GroundTruthEventRequest request) {
        var event = service.create(mediaAssetId, request.eventType(), request.timestampMs(),
                request.startTimeMs(), request.endTimeMs(), request.note());
        return ResponseEntity.created(URI.create("/api/media-assets/" + mediaAssetId
                        + "/ground-truth/events/" + event.id()))
                .body(GroundTruthEventResponse.from(event));
    }

    @PostMapping("/events/from-candidates/{candidateId}")
    @Operation(summary = "Explicitly confirm a candidate as ground truth")
    public ResponseEntity<GroundTruthEventResponse> confirmCandidate(@PathVariable UUID mediaAssetId,
                                                                     @PathVariable UUID candidateId,
                                                                     @RequestBody(required = false)
                                                                     GroundTruthNoteRequest note) {
        var event = service.confirmCandidate(mediaAssetId, candidateId,
                note == null ? null : note.note());
        return ResponseEntity.created(URI.create("/api/media-assets/" + mediaAssetId
                        + "/ground-truth/events/" + event.id()))
                .body(GroundTruthEventResponse.from(event));
    }

    @DeleteMapping("/events/{eventId}")
    @Operation(summary = "Remove an incorrect ground-truth annotation")
    public ResponseEntity<Void> delete(@PathVariable UUID mediaAssetId, @PathVariable UUID eventId) {
        service.delete(mediaAssetId, eventId);
        return ResponseEntity.noContent().build();
    }

    public record GroundTruthNoteRequest(String note) {
    }
}
