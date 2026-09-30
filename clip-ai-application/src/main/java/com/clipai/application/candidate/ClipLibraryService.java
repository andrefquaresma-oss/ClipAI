package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorage;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ClipLibraryService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final CandidateReviewRepository reviews;
    private final ClipStorage storage;

    public ClipLibraryService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                              CandidateReviewRepository reviews, ClipStorage storage) {
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.reviews = reviews;
        this.storage = storage;
    }

    public List<ClipLibraryItem> list() {
        return list(0, 100, null, null, null, null).items();
    }

    public ClipLibraryPage list(int page, int size, String search, FootballEventType eventType,
                                UUID mediaAssetId, CandidateReviewStatus reviewStatus) {
        return list(page, size, search, eventType, mediaAssetId, reviewStatus, null);
    }

    public ClipLibraryPage list(int page, int size, String search, FootballEventType eventType,
                                UUID mediaAssetId, CandidateReviewStatus reviewStatus, UUID detectionRunId) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        List<ClipLibraryItem> items = new ArrayList<>();
        int assetPage = 0;
        long totalElements;
        do {
            var result = mediaAssets.findAll(assetPage, 100, null);
            totalElements = result.totalElements();
            for (var asset : result.items()) {
                if (mediaAssetId != null && !mediaAssetId.equals(asset.getId())) {
                    continue;
                }
                for (CandidateEvent candidate : candidates.findByMediaAssetId(asset.getId())) {
                    if (detectionRunId != null && !detectionRunId.equals(candidate.detectionRunId())) {
                        continue;
                    }
                    CandidateReview review = reviews.findByCandidateId(candidate.id()).orElse(null);
                    CandidateReviewStatus actualReviewStatus = review == null
                            ? CandidateReviewStatus.UNREVIEWED : review.status();
                    if (reviewStatus != null && actualReviewStatus != reviewStatus) {
                        continue;
                    }
                    if (eventType != null && candidate.eventType() != eventType) {
                        continue;
                    }
                    if (search != null && !search.isBlank()
                            && !asset.getTitle().toLowerCase(java.util.Locale.ROOT)
                            .contains(search.trim().toLowerCase(java.util.Locale.ROOT))) {
                        continue;
                    }
                    CandidateClipCategory category = CandidateClipCategory.forEventType(candidate.eventType());
                    var location = storage.find(asset.getId(), category, candidate.id());
                    if (location.isEmpty()) {
                        continue;
                    }
                    long start = review != null && review.hasManualBoundary()
                            ? review.manualStartTimeMs() : candidate.startTimeMs();
                    long end = review != null && review.hasManualBoundary()
                            ? review.manualEndTimeMs() : candidate.endTimeMs();
                    items.add(new ClipLibraryItem(asset.getId(), asset.getTitle(), candidate.id(),
                            candidate.eventType(), actualReviewStatus, start, end,
                            "/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id()
                                    + "/clip?inline=true", candidate.detectionRunId()));
                }
            }
            assetPage++;
        } while (assetPage * 100L < totalElements);
        List<ClipLibraryItem> ordered = items.stream().sorted(Comparator.comparing(ClipLibraryItem::mediaAssetTitle)
                .thenComparingLong(ClipLibraryItem::startTimeMs)).toList();
        int from = Math.min(page * size, ordered.size());
        int to = Math.min(from + size, ordered.size());
        return new ClipLibraryPage(ordered.subList(from, to), page, size, ordered.size());
    }
}
