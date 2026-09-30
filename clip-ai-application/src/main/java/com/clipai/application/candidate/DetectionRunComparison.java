package com.clipai.application.candidate;

import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateObservation;

import java.util.List;
import java.util.Map;

public record DetectionRunComparison(DetectionRunSummary leftRun, DetectionRunSummary rightRun,
                                     List<DetectionRunCandidateMatch> matchingCandidates,
                                     List<CandidateEvent> onlyInLeft, List<CandidateEvent> onlyInRight,
                                     ObservationComparison observations) {
    public DetectionRunComparison {
        matchingCandidates = List.copyOf(matchingCandidates);
        onlyInLeft = List.copyOf(onlyInLeft);
        onlyInRight = List.copyOf(onlyInRight);
    }

    public record ObservationComparison(long centerTimestampMs, long windowMs,
                                        Map<String, List<CandidateObservation>> left,
                                        Map<String, List<CandidateObservation>> right) {
        public ObservationComparison {
            left = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(immutableGroups(left)));
            right = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(immutableGroups(right)));
        }

        private static Map<String, List<CandidateObservation>> immutableGroups(
                Map<String, List<CandidateObservation>> groups) {
            return groups.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue()),
                    (first, second) -> first, java.util.LinkedHashMap::new));
        }
    }
}
