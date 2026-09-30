package com.clipai.api.candidate;

import com.clipai.application.candidate.ClipLibraryItem;

import java.util.List;
import java.util.UUID;

public record ClipLibraryResponse(List<Item> items, int page, int size, long totalElements, int totalPages) {
    public ClipLibraryResponse {
        items = List.copyOf(items);
    }

    public static ClipLibraryResponse from(List<ClipLibraryItem> items) {
        return from(new com.clipai.application.candidate.ClipLibraryPage(items, 0,
                Math.max(1, items.size()), items.size()));
    }

    public static ClipLibraryResponse from(com.clipai.application.candidate.ClipLibraryPage page) {
        int totalPages = page.totalElements() == 0 ? 0
                : (int) Math.ceil((double) page.totalElements() / page.size());
        return new ClipLibraryResponse(page.items().stream().map(item -> new Item(item.mediaAssetId(),
                item.mediaAssetTitle(), item.candidateId(), item.eventType(), item.reviewStatus(),
                item.startTimeMs(), item.endTimeMs(), item.downloadUrl(), item.detectionRunId())).toList(), page.page(), page.size(),
                page.totalElements(), totalPages);
    }

    public record Item(UUID mediaAssetId, String mediaAssetTitle, UUID candidateId,
                       com.clipai.domain.candidate.FootballEventType eventType,
                       com.clipai.application.candidate.CandidateReviewStatus reviewStatus,
                       long startTimeMs, long endTimeMs, String downloadUrl, UUID detectionRunId) {
    }
}
