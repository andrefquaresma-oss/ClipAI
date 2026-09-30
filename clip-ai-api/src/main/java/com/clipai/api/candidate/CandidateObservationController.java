package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateObservationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/observations")
public class CandidateObservationController {
    private final CandidateObservationQueryService observations;

    public CandidateObservationController(CandidateObservationQueryService observations) {
        this.observations = observations;
    }

    @GetMapping
    @Operation(summary = "List detector observations in a bounded media-time range",
            description = "Returns persisted transcript and audio observations separately from candidate conclusions.")
    public List<CandidateObservationResponse> list(
            @PathVariable UUID mediaAssetId,
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(defaultValue = "500") int limit) {
        return observations.find(mediaAssetId, startTimeMs, endTimeMs, limit).stream()
                .map(CandidateObservationResponse::from).toList();
    }
}
