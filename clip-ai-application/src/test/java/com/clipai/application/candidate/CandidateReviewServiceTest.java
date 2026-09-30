package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetPage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateEventStatus;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void savesReviewSeparatelyAndUsesManualBoundariesAsEffectiveWindow() {
        Fixture fixture = fixture();

        CandidateReviewResult result = fixture.service().update(fixture.asset().getId(),
                fixture.candidate().id(), CandidateReviewStatus.CONFIRMED, 12_000L, 48_000L);

        assertEquals(CandidateEventStatus.DETECTED, result.candidate().status());
        assertEquals(CandidateReviewStatus.CONFIRMED, result.review().status());
        assertEquals(10_000, result.candidate().startTimeMs());
        assertEquals(12_000, result.effectiveStartTimeMs());
        assertEquals(48_000, result.effectiveEndTimeMs());
        assertEquals(60_000, result.maximumClipDurationMs());
    }

    @Test
    void rejectsInvalidOutOfBoundsAndOverlongManualBoundaries() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.service().update(
                fixture.asset().getId(), fixture.candidate().id(), CandidateReviewStatus.CONFIRMED,
                -1L, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> fixture.service().update(
                fixture.asset().getId(), fixture.candidate().id(), CandidateReviewStatus.CONFIRMED,
                10_000L, 9_000L));
        assertThrows(IllegalArgumentException.class, () -> fixture.service().update(
                fixture.asset().getId(), fixture.candidate().id(), CandidateReviewStatus.CONFIRMED,
                10_000L, 70_001L));
        assertNull(fixture.saved().get());
    }

    @Test
    void clearingManualBoundariesRestoresAutomaticWindow() {
        Fixture fixture = fixture();
        fixture.service().update(fixture.asset().getId(), fixture.candidate().id(),
                CandidateReviewStatus.UNREVIEWED, 12_000L, 48_000L);

        CandidateReviewResult cleared = fixture.service().update(fixture.asset().getId(),
                fixture.candidate().id(), CandidateReviewStatus.CONFIRMED, null, null);

        assertEquals(10_000, cleared.effectiveStartTimeMs());
        assertEquals(50_000, cleared.effectiveEndTimeMs());
        assertEquals(CandidateReviewStatus.CONFIRMED, cleared.review().status());
    }

    @Test
    void storesHumanRejectionReasonWithoutChangingSystemDetection() {
        Fixture fixture = fixture();

        CandidateReviewResult result = fixture.service().update(fixture.asset().getId(),
                fixture.candidate().id(), CandidateReviewStatus.REJECTED,
                null, null, "Replay, not a new event", null, HumanRejectionReason.REPLAY);

        assertEquals(CandidateEventStatus.DETECTED, result.candidate().status());
        assertEquals(CandidateReviewStatus.REJECTED, result.review().status());
        assertEquals(HumanRejectionReason.REPLAY, result.review().humanRejectionReason());
        assertEquals("Replay, not a new event", result.review().note());
    }

    private static Fixture fixture() {
        UUID assetId = UUID.randomUUID();
        CandidateEvent candidate = CandidateEvent.detected(assetId, 10_000, 50_000, 30_000,
                FootballEventType.GOAL, 0.9,
                List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        FootballEventType.GOAL, 0.9, 30_000, "goal")), "goal", NOW);
        MediaAsset asset = MediaAsset.restore(assetId, "LOCAL_UPLOAD", null, null, "asset",
                ContentType.SPORTS, 80_000L, "media/" + assetId + "/source.mp4",
                MediaAssetStatus.COMPLETED, NOW, NOW);
        MediaAssetRepository mediaAssets = new MediaAssetRepository() {
            @Override public MediaAsset save(MediaAsset mediaAsset) { return mediaAsset; }
            @Override public Optional<MediaAsset> findById(UUID id) {
                return id.equals(assetId) ? Optional.of(asset) : Optional.empty();
            }
            @Override public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                return new MediaAssetPage(List.of(asset), page, size, 1);
            }
        };
        CandidateEventRepository candidates = new CandidateEventRepository() {
            @Override public List<CandidateEvent> findByMediaAssetId(UUID mediaAssetId) {
                return mediaAssetId.equals(assetId) ? List.of(candidate) : List.of();
            }
            @Override public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID mediaAssetId) {
                return id.equals(candidate.id()) && mediaAssetId.equals(assetId)
                        ? Optional.of(candidate) : Optional.empty();
            }
            @Override public void replaceForMediaAsset(UUID mediaAssetId, List<CandidateEvent> events) { }
        };
        AtomicReference<CandidateReview> saved = new AtomicReference<>();
        CandidateReviewRepository reviews = new CandidateReviewRepository() {
            @Override public Optional<CandidateReview> findByCandidateId(UUID candidateId) {
                CandidateReview value = saved.get();
                return value != null && value.candidateId().equals(candidateId)
                        ? Optional.of(value) : Optional.empty();
            }
            @Override public CandidateReview save(CandidateReview review) {
                saved.set(review);
                return review;
            }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new Fixture(asset, candidate,
                new CandidateReviewService(mediaAssets, candidates, reviews, clock, 60_000), saved);
    }

    private record Fixture(MediaAsset asset, CandidateEvent candidate, CandidateReviewService service,
                           AtomicReference<CandidateReview> saved) {
    }
}
