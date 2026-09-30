package com.clipai.application.analytics;

import java.util.Map;

public record ControlCenterAnalytics(long mediaAssets, Map<String, Long> mediaByStatus,
                                     long candidateEvents, Map<String, Long> eventsByType,
                                     Map<String, Long> eventsByDetectionStatus,
                                     long generatedClips, long reviewedMatches,
                                     long completedGroundTruthMatches, long groundTruthEvents,
                                     long evaluationReadyMatches,
                                     long eventsAwaitingReview, long rejectedCandidatesAwaitingReview,
                                     Map<String, Long> detectionRunsByStatus,
                                     Map<String, Long> processingStagesByStatus,
                                     String evaluationSufficiencyRule) {
    public ControlCenterAnalytics {
        mediaByStatus = Map.copyOf(mediaByStatus);
        eventsByType = Map.copyOf(eventsByType);
        eventsByDetectionStatus = Map.copyOf(eventsByDetectionStatus);
        detectionRunsByStatus = Map.copyOf(detectionRunsByStatus);
        processingStagesByStatus = Map.copyOf(processingStagesByStatus);
    }
}
