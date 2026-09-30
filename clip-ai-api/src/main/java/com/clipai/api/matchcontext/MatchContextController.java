package com.clipai.api.matchcontext;

import com.clipai.application.matchcontext.MatchContext;
import com.clipai.application.matchcontext.MatchContextService;
import com.clipai.application.matchcontext.MatchScoreTransition;
import com.clipai.application.matchcontext.MatchStructureMarker;
import com.clipai.application.matchcontext.MatchStructureMarkerType;
import com.clipai.application.matchcontext.ScoreTransitionSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/match-context")
@Tag(name = "Match context", description = "Match structure markers and score transitions")
public class MatchContextController {
    private final MatchContextService service;

    public MatchContextController(MatchContextService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get timeline context for a match")
    public MatchContextResponse get(@PathVariable UUID mediaAssetId) {
        MatchContext context = service.get(mediaAssetId);
        return new MatchContextResponse(context.structureMarkers(), context.scoreTransitions());
    }

    @PutMapping("/structure-markers/{type}")
    @Operation(summary = "Set or move a match structure marker")
    public MatchStructureMarker setMarker(@PathVariable UUID mediaAssetId,
                                          @PathVariable MatchStructureMarkerType type,
                                          @Valid @RequestBody StructureMarkerRequest request) {
        return service.setStructureMarker(mediaAssetId, type, request.timestampMs());
    }

    @DeleteMapping("/structure-markers/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMarker(@PathVariable UUID mediaAssetId,
                             @PathVariable MatchStructureMarkerType type) {
        service.deleteStructureMarker(mediaAssetId, type);
    }

    @PostMapping("/score-transitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a score transition")
    public MatchScoreTransition createScore(@PathVariable UUID mediaAssetId,
                                            @Valid @RequestBody ScoreTransitionRequest request) {
        return service.createScoreTransition(mediaAssetId, request.timestampMs(), request.homeScore(),
                request.awayScore(), request.source(), request.confidence());
    }

    @PutMapping("/score-transitions/{transitionId}")
    @Operation(summary = "Correct a score transition")
    public MatchScoreTransition updateScore(@PathVariable UUID mediaAssetId,
                                            @PathVariable UUID transitionId,
                                            @Valid @RequestBody ScoreTransitionRequest request) {
        return service.updateScoreTransition(mediaAssetId, transitionId, request.timestampMs(),
                request.homeScore(), request.awayScore(), request.source(), request.confidence());
    }

    @DeleteMapping("/score-transitions/{transitionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScore(@PathVariable UUID mediaAssetId, @PathVariable UUID transitionId) {
        service.deleteScoreTransition(mediaAssetId, transitionId);
    }

    public record MatchContextResponse(List<MatchStructureMarker> structureMarkers,
                                       List<MatchScoreTransition> scoreTransitions) {
    }

    public record StructureMarkerRequest(@Min(0) long timestampMs) {
    }

    public record ScoreTransitionRequest(@Min(0) long timestampMs,
                                         @Min(0) int homeScore, @Min(0) int awayScore,
                                         @NotNull ScoreTransitionSource source,
                                         @Min(0) @Max(1) Double confidence) {
    }
}
