package com.clipai.api.candidate;

import com.clipai.application.candidate.CandidateClipBatchSchedulingException;
import com.clipai.application.candidate.CandidateClipBatchStatus;
import com.clipai.application.candidate.CandidateClipBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/media-assets/{mediaAssetId}/candidates/clips")
@Tag(name = "Football clips", description = "Generate and retrieve candidate clips for manual review")
public class CandidateClipBatchController {
    private final CandidateClipBatchService batches;
    private final AsyncCandidateClipBatchWorker worker;

    public CandidateClipBatchController(CandidateClipBatchService batches, AsyncCandidateClipBatchWorker worker) {
        this.batches = batches;
        this.worker = worker;
    }

    @PostMapping
    @Operation(summary = "Generate clips for detected candidates",
            description = "Queues MP4 generation for all detected candidates. Rejected candidates are excluded. "
                    + "Provide candidateIds to generate only explicitly selected candidates, including rejected ones. "
                    + "GOAL, card, and SHOT candidates use their dedicated folders; other event types use "
                    + "the other folder. Existing clips are reused. Clip windows use the persisted candidate "
                    + "startTimeMs and endTimeMs without adding pre-roll.")
    @ApiResponse(responseCode = "202", description = "Clip generation queued; poll the same path for progress")
    public ResponseEntity<CandidateClipBatchResponse> generate(@PathVariable UUID mediaAssetId,
                                                               @RequestBody(required = false)
                                                               CandidateClipBatchRequest request) {
        var batch = batches.start(mediaAssetId, request == null ? null : request.candidateIds());
        if (batch.status() == CandidateClipBatchStatus.PROCESSING) {
            try {
                worker.generate(mediaAssetId, batch.batchId());
            } catch (RuntimeException exception) {
                batches.failScheduling(mediaAssetId, batch.batchId());
                throw new CandidateClipBatchSchedulingException(mediaAssetId, exception);
            }
        }
        URI statusUri = URI.create("/api/media-assets/" + mediaAssetId + "/candidates/clips");
        return ResponseEntity.accepted().location(statusUri).body(CandidateClipBatchResponse.from(batch));
    }

    @GetMapping
    @Operation(summary = "Get candidate clip generation status",
            description = "Returns per-candidate status, timestamp window, storage key, and download URL "
                    + "for the most recently requested batch for this media asset.")
    @ApiResponse(responseCode = "200", description = "Current batch state")
    public CandidateClipBatchResponse getLatest(@PathVariable UUID mediaAssetId) {
        return CandidateClipBatchResponse.from(batches.getLatest(mediaAssetId));
    }
}
