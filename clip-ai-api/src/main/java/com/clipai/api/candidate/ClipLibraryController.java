package com.clipai.api.candidate;

import com.clipai.application.candidate.ClipLibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.application.candidate.CandidateReviewStatus;
import java.util.UUID;

@RestController
@RequestMapping("/api/clips")
@Tag(name = "Football clips", description = "Searchable library of generated event clips")
public class ClipLibraryController {
    private final ClipLibraryService clips;

    public ClipLibraryController(ClipLibraryService clips) {
        this.clips = clips;
    }

    @GetMapping
    @Operation(summary = "List generated clips without exposing storage paths")
    public ClipLibraryResponse list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) FootballEventType eventType,
                                    @RequestParam(required = false) UUID mediaAssetId,
                                    @RequestParam(required = false) CandidateReviewStatus reviewStatus,
                                    @RequestParam(required = false) UUID detectionRunId) {
        return ClipLibraryResponse.from(
                clips.list(page, size, search, eventType, mediaAssetId, reviewStatus, detectionRunId));
    }
}
