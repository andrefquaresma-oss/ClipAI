package com.clipai.domain.candidate;

import java.util.List;
import java.util.Objects;

public record CandidateScoreComponent(CandidateScoreComponentType type, double evidenceConfidence, double weight,
                                      double contribution,
                                      List<CandidateSignalType> supportingSignalTypes,
                                      String explanation) {
    public CandidateScoreComponent {
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(evidenceConfidence) || evidenceConfidence < 0 || evidenceConfidence > 1) {
            throw new IllegalArgumentException("evidenceConfidence must be between zero and one");
        }
        if (!Double.isFinite(weight) || !Double.isFinite(contribution)) {
            throw new IllegalArgumentException("weight and contribution must be finite");
        }
        supportingSignalTypes = List.copyOf(Objects.requireNonNull(
                supportingSignalTypes, "supportingSignalTypes"));
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
        explanation = explanation.trim();
    }
}
