package com.clipai.application.analytics;

import com.clipai.application.candidate.ClipLibraryService;

public final class ControlCenterAnalyticsService {
    private final ControlCenterAnalyticsRepository analytics;
    private final ClipLibraryService clips;

    public ControlCenterAnalyticsService(ControlCenterAnalyticsRepository analytics, ClipLibraryService clips) {
        this.analytics = analytics;
        this.clips = clips;
    }

    public ControlCenterAnalytics get() {
        long clipCount = clips.list(0, 1, null, null, null, null).totalElements();
        return analytics.load(clipCount);
    }
}
