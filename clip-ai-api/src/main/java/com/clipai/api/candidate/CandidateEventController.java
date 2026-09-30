package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.candidate.CandidateEventQueryService;
import com.clipai.application.candidate.CandidateSort;
import com.clipai.application.candidate.ManualCandidateEventService;
import com.clipai.application.matchcontext.MatchContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/candidates")
@Tag(name = "Football candidates", description = "Detect and inspect timestamped football-event candidates")
public class CandidateEventController {
    private final CandidateDetectionScheduler scheduler;
    private final CandidateEventQueryService queries;
    private final ManualCandidateEventService manualEvents;
    private final MatchContextService matchContext;

    public CandidateEventController(CandidateDetectionScheduler scheduler, CandidateEventQueryService queries,
                                    ManualCandidateEventService manualEvents,
                                    MatchContextService matchContext) {
        this.scheduler = scheduler;
        this.queries = queries;
        this.manualEvents = manualEvents;
        this.matchContext = matchContext;
    }

    @PostMapping("/detect")
    @Operation(summary = "Start candidate detection",
            description = "Schedules transcript and audio signal analysis for a completed media asset.")
    public ResponseEntity<CandidateDetectionResponse> detect(@PathVariable UUID mediaAssetId) {
        var asset = scheduler.schedule(mediaAssetId);
        return ResponseEntity.accepted()
                .location(URI.create("/api/media-assets/" + mediaAssetId + "/candidates"))
                .body(CandidateDetectionResponse.from(asset));
    }

    @GetMapping
    @Operation(summary = "List detected candidates", description = "Candidates are sorted by score or timestamp.")
    public CandidateEventListResponse list(@PathVariable UUID mediaAssetId,
                                            @RequestParam(defaultValue = "score") String sort) {
        var context = matchContext.get(mediaAssetId);
        return CandidateEventListResponse.from(queries.list(mediaAssetId, parseSort(sort)),
                event -> com.clipai.application.matchcontext.MatchPhaseResolver.at(
                        context.structureMarkers(), event.triggerTimestampMs()));
    }

    @GetMapping("/{candidateId}")
    @Operation(summary = "Get a candidate",
            description = "Returns candidate evidence and a typed temporal sequence reconstructed from persisted signals.")
    public CandidateEventResponse get(@PathVariable UUID mediaAssetId, @PathVariable UUID candidateId) {
        var event = queries.get(mediaAssetId, candidateId);
        return CandidateEventResponse.from(event, matchContext.phaseAt(mediaAssetId, event.triggerTimestampMs()));
    }

    @PostMapping
    @Operation(summary = "Create a manually annotated event")
    public ResponseEntity<CandidateEventResponse> createManual(
            @PathVariable UUID mediaAssetId, @Valid @RequestBody ManualCandidateEventRequest request) {
        var created = manualEvents.create(mediaAssetId, request.eventType(), request.timestampMs(),
                request.startTimeMs(), request.endTimeMs(), request.note());
        return ResponseEntity.created(URI.create("/api/media-assets/" + mediaAssetId
                        + "/candidates/" + created.id()))
                .body(CandidateEventResponse.from(created,
                        matchContext.phaseAt(mediaAssetId, created.triggerTimestampMs())));
    }

    private static CandidateSort parseSort(String sort) {
        return switch (sort.toLowerCase(Locale.ROOT)) {
            case "score" -> CandidateSort.SCORE;
            case "timestamp", "time" -> CandidateSort.TIMESTAMP;
            default -> throw new IllegalArgumentException("sort must be score or timestamp");
        };
    }
}
