package com.clipai.application.candidate;

import java.util.List;

public record ClipLibraryPage(List<ClipLibraryItem> items, int page, int size, long totalElements) {
    public ClipLibraryPage {
        items = List.copyOf(items);
    }
}
