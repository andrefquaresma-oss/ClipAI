package com.clipai.application.groundtruth;

import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.CandidateReviewRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundTruthServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void onlyEnablesEvaluationAfterCompletedReviewAndMinimumAnnotationCount() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 4; index++) {
            fixture.service.create(fixture.asset.getId(), FootballEventType.GOAL,
                    (index + 1) * 30_000L, (index + 1) * 30_000L - 10_000,
                    (index + 1) * 30_000L + 10_000, null);
        }
        fixture.service.setStatus(fixture.asset.getId(), GroundTruthReviewStatus.COMPLETED);
        assertFalse(fixture.service.get(fixture.asset.getId()).metrics().sufficientForEvaluation());

        fixture.service.create(fixture.asset.getId(), FootballEventType.GOAL,
                150_000, 140_000, 160_000, "Fifth reviewed event");
        fixture.service.setStatus(fixture.asset.getId(), GroundTruthReviewStatus.COMPLETED);
        GroundTruthMetrics metrics = fixture.service.get(fixture.asset.getId()).metrics();
        assertTrue(metrics.sufficientForEvaluation());
        assertEquals(5, metrics.groundTruthCount());
        assertEquals(1, metrics.truePositive());
        assertEquals(4, metrics.falseNegative());
        assertEquals(0, metrics.falsePositive());
    }

    private static final class Fixture {
        private final MediaAsset asset = MediaAsset.restore(UUID.randomUUID(), "LOCAL_UPLOAD", null, null,
                "match", ContentType.SPORTS, 200_000L, "media/match.mp4",
                MediaAssetStatus.COMPLETED, NOW, NOW);
        private final InMemoryGroundTruth truth = new InMemoryGroundTruth();
        private final GroundTruthService service = new GroundTruthService(
                new MediaAssetRepository() {
                    @Override public MediaAsset save(MediaAsset value) { return value; }
                    @Override public Optional<MediaAsset> findById(UUID id) {
                        return id.equals(asset.getId()) ? Optional.of(asset) : Optional.empty();
                    }
                    @Override public MediaAssetPage findAll(int page, int size, MediaAssetStatus status) {
                        return new MediaAssetPage(List.of(asset), page, size, 1);
                    }
                },
                new CandidateEventRepository() {
                    private final CandidateEvent candidate = CandidateEvent.detected(asset.getId(), 20_000,
                            40_000, 30_000, FootballEventType.GOAL, 0.9,
                            List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                                    FootballEventType.GOAL, 0.9, 30_000, "goal")), null, NOW);
                    @Override public List<CandidateEvent> findByMediaAssetId(UUID id) {
                        return id.equals(asset.getId()) ? List.of(candidate) : List.of();
                    }
                    @Override public Optional<CandidateEvent> findByIdAndMediaAssetId(UUID id, UUID assetId) {
                        return findByMediaAssetId(assetId).stream().filter(item -> item.id().equals(id)).findFirst();
                    }
                    @Override public void replaceForMediaAsset(UUID id, List<CandidateEvent> events) { }
                },
                CandidateReviewRepository.none(), truth, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemoryGroundTruth implements GroundTruthRepository {
        private final List<GroundTruthEvent> events = new ArrayList<>();
        private GroundTruthMatchReview review;
        @Override public Optional<GroundTruthMatchReview> findReview(UUID id) {
            return review != null && review.mediaAssetId().equals(id) ? Optional.of(review) : Optional.empty();
        }
        @Override public GroundTruthMatchReview saveReview(GroundTruthMatchReview value) {
            review = value; return value;
        }
        @Override public List<GroundTruthEvent> findEvents(UUID id) {
            return events.stream().filter(event -> event.mediaAssetId().equals(id)).toList();
        }
        @Override public GroundTruthEvent saveEvent(GroundTruthEvent event) {
            events.add(event); return event;
        }
        @Override public void deleteEvent(UUID eventId, UUID mediaAssetId) {
            events.removeIf(event -> event.id().equals(eventId) && event.mediaAssetId().equals(mediaAssetId));
        }
    }
}
