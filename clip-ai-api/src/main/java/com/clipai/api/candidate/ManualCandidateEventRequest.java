package com.clipai.api.candidate;

import com.clipai.domain.candidate.FootballEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ManualCandidateEventRequest(@NotNull FootballEventType eventType,
                                          @PositiveOrZero long timestampMs,
                                          @PositiveOrZero long startTimeMs,
                                          @PositiveOrZero long endTimeMs,
                                          String note) {
}
