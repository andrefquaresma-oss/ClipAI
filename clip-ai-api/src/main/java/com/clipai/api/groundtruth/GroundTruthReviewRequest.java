package com.clipai.api.groundtruth;

import com.clipai.application.groundtruth.GroundTruthReviewStatus;
import jakarta.validation.constraints.NotNull;

public record GroundTruthReviewRequest(@NotNull GroundTruthReviewStatus status) {
}
