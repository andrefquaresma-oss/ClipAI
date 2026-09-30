package com.clipai.domain.media;

import com.clipai.domain.candidate.CandidateDetectionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaAssetTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void registersValidatedMetadataAsPending() {
        MediaAsset asset = MediaAsset.register("example", "https://example.com/vod/1", "  Title  ",
                ContentType.GAMING, now);

        assertEquals(MediaAssetStatus.PENDING, asset.getStatus());
        assertEquals("Title", asset.getTitle());
        assertEquals(now, asset.getCreatedAt());
        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.register("example", "file:///tmp/video", "Title", ContentType.GENERIC, now));
    }

    @Test
    void allowsOnlyTheExpectedLifecycleTransitions() {
        MediaAsset asset = MediaAsset.register("example", "https://example.com/vod/1", "Title",
                ContentType.GENERIC, now);
        assertThrows(IllegalStateException.class, () -> asset.startProcessing(now));

        asset.startDownloading(now.plusSeconds(1));
        asset.markReady("media/asset.mp4", 42_500L, now.plusSeconds(2));
        asset.startProcessing(now.plusSeconds(3));
        asset.markCompleted(now.plusSeconds(4));

        assertEquals(MediaAssetStatus.COMPLETED, asset.getStatus());
        assertThrows(IllegalStateException.class, () -> asset.markFailed(now.plusSeconds(5)));
    }

    @Test
    void acceptsStoredTranscribingLifecycleAndPersistsFailureReason() {
        MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "recording.mp4", ContentType.GENERIC, now);
        asset.markStored("media/" + asset.getId() + "/source.mp4", now.plusSeconds(1));

        assertEquals(MediaAssetStatus.STORED, asset.getStatus());
        assertThrows(IllegalStateException.class, () -> asset.startTranscribing(now.plusSeconds(2)));
        asset.startProcessing(now.plusSeconds(2));
        asset.startTranscribing(now.plusSeconds(3));
        asset.markFailed("Whisper worker unavailable", now.plusSeconds(4));

        assertEquals(MediaAssetStatus.FAILED, asset.getStatus());
        assertEquals("Whisper worker unavailable", asset.getFailureReason());
    }

    @Test
    void storesMatchPartAndDefaultsOlderUploadFactoriesToOther() {
        MediaAsset secondHalf = MediaAsset.registerUpload("LOCAL_UPLOAD", "second-half.mp4",
                ContentType.SPORTS, "second-half.mp4", "La Liga", "Barcelona", "Rayo Vallecano",
                java.time.LocalDate.of(2024, 8, 24), "es", MediaAssetPart.SECOND_HALF, now);

        assertEquals(MediaAssetPart.SECOND_HALF, secondHalf.getMatchPart());
        assertEquals(MediaAssetPart.OTHER, MediaAsset.registerUpload("LOCAL_UPLOAD", "other.mp4",
                ContentType.GENERIC, now).getMatchPart());
    }

    @Test
    void tracksCandidateDetectionIndependentlyAndAllowsRetryAfterFailure() {
        MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "match.mp4", ContentType.SPORTS, now);
        asset.markStored("media/" + asset.getId() + "/source.mp4", now.plusSeconds(1));
        asset.startProcessing(now.plusSeconds(2));
        asset.markCompleted(now.plusSeconds(3));

        assertEquals(CandidateDetectionStatus.NOT_STARTED, asset.getCandidateDetectionStatus());
        asset.startCandidateDetection(now.plusSeconds(4));
        assertEquals(CandidateDetectionStatus.PROCESSING, asset.getCandidateDetectionStatus());
        assertThrows(IllegalStateException.class, () -> asset.startCandidateDetection(now.plusSeconds(5)));
        asset.failCandidateDetection("Audio analysis failed", now.plusSeconds(5));
        assertEquals(CandidateDetectionStatus.FAILED, asset.getCandidateDetectionStatus());

        asset.startCandidateDetection(now.plusSeconds(6));
        asset.completeCandidateDetection(now.plusSeconds(7));
        assertEquals(CandidateDetectionStatus.COMPLETED, asset.getCandidateDetectionStatus());
        org.junit.jupiter.api.Assertions.assertNull(asset.getCandidateDetectionFailureReason());
    }
}
