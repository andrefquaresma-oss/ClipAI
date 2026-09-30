package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.candidate.DetectionRunComparisonService;
import com.clipai.application.candidate.DetectionRunQueryService;
import com.clipai.application.candidate.DetectionRunSummary;
import com.clipai.application.matchcontext.MatchContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/detection-runs")
@Tag(name = "Detection runs", description = "Append-only candidate detection executions and comparisons")
public class DetectionRunController {
    private final CandidateDetectionScheduler scheduler;
    private final DetectionRunQueryService runs;
    private final DetectionRunComparisonService comparisons;
    private final MatchContextService matchContext;

    public DetectionRunController(CandidateDetectionScheduler scheduler,
                                  DetectionRunQueryService runs,
                                  DetectionRunComparisonService comparisons,
                                  MatchContextService matchContext) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.comparisons = comparisons;
        this.matchContext = matchContext;
    }

    @PostMapping
    @Operation(summary = "Start a new safe detection run",
            description = "Creates a new run without replacing candidates, observations, reviews, or clips from prior runs.")
    public ResponseEntity<DetectionRunResponse> create(@PathVariable UUID mediaAssetId) {
        var run = scheduler.scheduleRun(mediaAssetId);
        DetectionRunSummary summary = runs.get(mediaAssetId, run.id().toString());
        return ResponseEntity.accepted()
                .location(URI.create("/api/media-assets/" + mediaAssetId + "/detection-runs/" + run.id()))
                .body(DetectionRunResponse.from(summary));
    }

    @GetMapping
    @Operation(summary = "List detection runs, including the read-only legacy baseline")
    public List<DetectionRunResponse> list(@PathVariable UUID mediaAssetId) {
        return runs.list(mediaAssetId).stream().map(DetectionRunResponse::from).toList();
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get one detection run")
    public DetectionRunResponse get(@PathVariable UUID mediaAssetId, @PathVariable String runId) {
        return DetectionRunResponse.from(runs.get(mediaAssetId, runId));
    }

    @GetMapping("/{runId}/candidates")
    @Operation(summary = "List candidates produced by one run")
    public List<CandidateEventResponse> candidates(@PathVariable UUID mediaAssetId,
                                                    @PathVariable String runId) {
        return runs.candidates(mediaAssetId, runId).stream()
                .map(event -> {
                    List<UUID> sourceObservationIds = runs.observations(mediaAssetId, runId,
                                    event.startTimeMs(), event.endTimeMs(), 1000).stream()
                            .filter(observation -> event.signals().contains(observation.signal()))
                            .map(observation -> observation.id()).toList();
                    return CandidateEventResponse.from(event,
                            matchContext.phaseAt(mediaAssetId, event.triggerTimestampMs()),
                            sourceObservationIds);
                })
                .toList();
    }

    @GetMapping("/{runId}/observations")
    @Operation(summary = "List observations produced by one run in a bounded time range")
    public List<CandidateObservationResponse> observations(
            @PathVariable UUID mediaAssetId,
            @PathVariable String runId,
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(defaultValue = "500") int limit) {
        return runs.observations(mediaAssetId, runId, startTimeMs, endTimeMs, limit).stream()
                .map(CandidateObservationResponse::from).toList();
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare candidates and observations from two runs")
    public DetectionRunComparisonResponse compare(
            @PathVariable UUID mediaAssetId,
            @RequestParam String leftRunId,
            @RequestParam String rightRunId,
            @RequestParam(required = false) Long centerTimestampMs,
            @RequestParam(required = false) Long windowMs) {
        var comparison = comparisons.compare(mediaAssetId, leftRunId, rightRunId,
                centerTimestampMs, windowMs);
        return DetectionRunComparisonResponse.from(comparison,
                event -> matchContext.phaseAt(mediaAssetId, event.triggerTimestampMs()));
    }
}
