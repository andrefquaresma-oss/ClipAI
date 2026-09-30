package com.clipai.api.scoreboard;

import com.clipai.application.scoreboard.ScoreboardAnalysisService;
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
@RequestMapping("/api/media-assets/{mediaAssetId}/scoreboard-analyses")
@Tag(name = "Scoreboard OCR", description = "Independent OCR score evidence and diagnostics")
public class ScoreboardAnalysisController {
    private final ScoreboardAnalysisService analyses;

    public ScoreboardAnalysisController(ScoreboardAnalysisService analyses) {
        this.analyses = analyses;
    }

    @PostMapping
    @Operation(summary = "Start an isolated OCR scoreboard analysis")
    public ResponseEntity<ScoreboardAnalysisResponse> start(@PathVariable UUID mediaAssetId) {
        var analysis = analyses.start(mediaAssetId);
        return ResponseEntity.accepted()
                .location(URI.create("/api/media-assets/" + mediaAssetId
                        + "/scoreboard-analyses/" + analysis.id()))
                .body(ScoreboardAnalysisResponse.from(analysis));
    }

    @GetMapping
    @Operation(summary = "List OCR analyses without changing detector run results")
    public List<ScoreboardAnalysisResponse> list(@PathVariable UUID mediaAssetId) {
        return analyses.list(mediaAssetId).stream().map(ScoreboardAnalysisResponse::from).toList();
    }

    @GetMapping("/{analysisId}")
    @Operation(summary = "Get OCR analysis status and summary counts")
    public ScoreboardAnalysisResponse get(@PathVariable UUID mediaAssetId,
                                          @PathVariable UUID analysisId) {
        return ScoreboardAnalysisResponse.from(analyses.get(mediaAssetId, analysisId));
    }

    @GetMapping("/{analysisId}/observations")
    @Operation(summary = "Inspect raw OCR, stable score states, transitions, and reversals")
    public List<ScoreboardObservationResponse> observations(
            @PathVariable UUID mediaAssetId,
            @PathVariable UUID analysisId,
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(defaultValue = "1000") int limit) {
        return analyses.observations(mediaAssetId, analysisId, startTimeMs, endTimeMs, limit)
                .stream().map(ScoreboardObservationResponse::from).toList();
    }
}
