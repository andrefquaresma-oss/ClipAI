package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipCategory;
import com.clipai.application.candidate.CandidateReviewService;
import com.clipai.application.candidate.CandidateReviewStatus;
import com.clipai.application.candidate.RejectedCandidateReviewClipService;
import com.clipai.application.ports.ClipStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/candidates/{candidateId}/review")
@Tag(name = "Candidate review", description = "Human review state and manual event clip boundaries")
public class CandidateReviewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(CandidateReviewController.class);
    private final CandidateReviewService reviews;
    private final ClipStorage clips;
    private final RejectedCandidateReviewClipService rejectedReviewClips;

    public CandidateReviewController(CandidateReviewService reviews, ClipStorage clips,
                                     RejectedCandidateReviewClipService rejectedReviewClips) {
        this.reviews = reviews;
        this.clips = clips;
        this.rejectedReviewClips = rejectedReviewClips;
    }

    @GetMapping
    @Operation(summary = "Get review state and effective clip boundaries")
    public CandidateReviewResponse get(@PathVariable UUID mediaAssetId, @PathVariable UUID candidateId) {
        return response(mediaAssetId, reviews.get(mediaAssetId, candidateId));
    }

    @PutMapping
    @Operation(summary = "Save review status and optional manual clip boundaries",
            description = "The automatic detection status and boundaries remain unchanged. Provide both manual "
                    + "timestamps to override the clip window, or null for both to restore the automatic window.")
    public CandidateReviewResponse update(@PathVariable UUID mediaAssetId, @PathVariable UUID candidateId,
                                          @Valid @RequestBody CandidateReviewRequest request) {
        var result = reviews.update(mediaAssetId, candidateId, request.status(),
                request.manualStartTimeMs(), request.manualEndTimeMs(), request.note(),
                request.eventTypeOverride(), request.humanRejectionReason());
        Boolean cleanupFailed = null;
        if (result.candidate().status() == com.clipai.domain.candidate.CandidateEventStatus.REJECTED
                && request.status() == CandidateReviewStatus.REJECTED) {
            cleanupFailed = !rejectedReviewClips.deleteAfterConfirmation(mediaAssetId, candidateId);
            if (cleanupFailed) {
                LOGGER.warn("Unable to clean temporary rejected-candidate review clip mediaAssetId={} candidateId={}",
                        mediaAssetId, candidateId);
            }
        }
        return response(mediaAssetId, result, cleanupFailed);
    }

    private CandidateReviewResponse response(UUID mediaAssetId,
                                             com.clipai.application.candidate.CandidateReviewResult result) {
        return response(mediaAssetId, result, null);
    }

    private CandidateReviewResponse response(UUID mediaAssetId,
                                             com.clipai.application.candidate.CandidateReviewResult result,
                                             Boolean cleanupFailed) {
        var category = CandidateClipCategory.forEventType(result.candidate().eventType());
        boolean generated = clips.find(mediaAssetId, category, result.candidate().id()).isPresent();
        return CandidateReviewResponse.from(result, generated, cleanupFailed);
    }
}
