package com.clipai.api;

import com.clipai.application.candidate.CandidateDetectionTrigger;
import com.clipai.application.candidate.CandidateClipCategory;
import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.CandidateObservationRepository;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.application.ports.ClipStorage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.scoreboard.ScoreboardAnalysisProcessor;
import com.clipai.application.scoreboard.ScoreboardAnalysisRepository;
import com.clipai.application.scoreboard.ScoreboardAnalysisTrigger;
import com.clipai.application.scoreboard.ScoreboardOcrResult;
import com.clipai.application.scoreboard.ScoreboardOcrService;
import com.clipai.domain.transcript.Transcript;
import com.clipai.domain.transcript.TranscriptSegment;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.CandidateScoreComponent;
import com.clipai.domain.candidate.CandidateScoreComponentType;
import com.clipai.domain.candidate.CandidateSignal;
import com.clipai.domain.candidate.CandidateSignalType;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.ContentType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.scoreboard.ScoreboardObservation;
import com.clipai.domain.transcript.TranscriptStatus;
import com.clipai.infrastructure.persistence.jpa.MediaAssetJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MediaAssetHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("clipai")
            .withUsername("clipai")
            .withPassword("clipai");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("media.storage.root",
                () -> Path.of("target", "clip-ai-test-storage").toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MediaAssetJpaRepository mediaAssetRepository;

    @Autowired
    MediaAssetRepository mediaAssetDomainRepository;

    @Autowired
    TranscriptRepository transcriptRepository;

    @Autowired
    MediaStorage mediaStorage;

    @Autowired
    ClipStorage clipStorage;

    @Autowired
    CandidateEventRepository candidateEventRepository;

    @Autowired
    CandidateObservationRepository candidateObservationRepository;

    @Autowired
    ScoreboardAnalysisRepository scoreboardAnalysisRepository;

    @Autowired
    ScoreboardAnalysisProcessor scoreboardAnalysisProcessor;

    @MockitoBean
    MediaProcessingTrigger processingTrigger;

    @MockitoBean
    CandidateDetectionTrigger candidateDetectionTrigger;

    @MockitoBean
    VideoClipper videoClipper;

    @MockitoBean
    ScoreboardAnalysisTrigger scoreboardAnalysisTrigger;

    @MockitoBean
    ScoreboardOcrService scoreboardOcrService;

    @Test
    void directCandidateReviewUrlRedirectsToTheControlCenterRoute() throws Exception {
        UUID mediaAssetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();

        mockMvc.perform(get("/assets/" + mediaAssetId + "/candidates/" + candidateId))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/#/assets/" + mediaAssetId + "/candidates/" + candidateId));
    }

    @Test
    void registersMetadataPersistsItInPostgresAndServesGetAndList() throws Exception {
        mediaAssetRepository.deleteAll();
        String body = """
                {
                  "source": "web",
                  "sourceUrl": "https://example.com/vod/episode-1",
                  "title": "Episode 1",
                  "contentType": "PODCAST"
                }
                """;

        String location = mockMvc.perform(post("/api/media-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.source").value("web"))
                .andReturn().getResponse().getHeader("Location");

        org.junit.jupiter.api.Assertions.assertNotNull(location);
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Episode 1"))
                .andExpect(jsonPath("$.contentType").value("PODCAST"));
        mockMvc.perform(get("/api/media-assets").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/media-assets").param("search", "episode").param("sort", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("Episode 1"));

        UUID mediaAssetId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        Transcript transcript = Transcript.create(mediaAssetId, "en", Instant.parse("2026-01-01T00:00:00Z"));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 0, 135, 1_950, "First segment"));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 1, 2_025, 3_110, "Second segment"));
        Transcript storedTranscript = transcriptRepository.save(transcript);

        var persistedTranscript = transcriptRepository.findByMediaAssetId(mediaAssetId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(storedTranscript.getId(), persistedTranscript.getId());
        org.junit.jupiter.api.Assertions.assertEquals(135,
                persistedTranscript.getSegments().getFirst().startTimeMs());
        org.junit.jupiter.api.Assertions.assertEquals(3_110,
                persistedTranscript.getSegments().getLast().endTimeMs());
        mockMvc.perform(get("/api/media-assets/" + mediaAssetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcript.status").value("PENDING"))
                .andExpect(jsonPath("$.transcript.segmentCount").value(2));
        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/transcript"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments", hasSize(2)))
                .andExpect(jsonPath("$.segments[0].startTimeMs").value(135));

        UUID missingId = UUID.randomUUID();
        mockMvc.perform(get("/api/media-assets/" + missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Media asset not found: " + missingId));

        mockMvc.perform(post("/api/media-assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "web",
                                  "sourceUrl": "file:///private/video.mp4",
                                  "title": "Invalid URL",
                                  "contentType": "GENERIC"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request is invalid"));
    }

    @Test
    void persistsIndependentObservationsAndReturnsCandidateScoreAndMatchPhase() throws Exception {
        MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "observability.mp4",
                ContentType.SPORTS, Instant.parse("2026-01-01T00:00:00Z"));
        mediaAssetDomainRepository.save(asset);
        UUID mediaAssetId = asset.getId();
        CandidateSignal observationSignal = new CandidateSignal(CandidateSignalType.AUDIO_SPIKE,
                null, 0.82, 12_500, "Local audio intensity increased");
        candidateObservationRepository.replaceForMediaAsset(mediaAssetId, List.of(observationSignal));

        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/observations")
                        .param("startTimeMs", "0").param("endTimeMs", "20000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("AUDIO_SPIKE"))
                .andExpect(jsonPath("$[0].timestampMs").value(12500));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/media-assets/" + mediaAssetId + "/match-context/structure-markers/KICKOFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestampMs\":10000}"))
                .andExpect(status().isOk());

        CandidateEvent candidate = CandidateEvent.detected(mediaAssetId, 11_000, 20_000, 15_000,
                FootballEventType.GOAL, 0.76, List.of(new CandidateSignal(
                        CandidateSignalType.TRANSCRIPT_GOAL, FootballEventType.GOAL,
                        0.90, 15_000, "Scoring phrase")), "Scoring phrase",
                Instant.parse("2026-01-01T00:00:00Z"), List.of(new CandidateScoreComponent(
                        CandidateScoreComponentType.EVENT_SPECIFIC_EVIDENCE, 0.90, 0.8,
                        0.72, List.of(CandidateSignalType.TRANSCRIPT_GOAL),
                        "Event-specific transcript evidence")));
        candidateEventRepository.replaceForMediaAsset(mediaAssetId, List.of(candidate));
        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/candidates")
                        .param("sort", "timestamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].matchPhase").value("FIRST_HALF"))
                .andExpect(jsonPath("$.events[0].scoreContributions", hasSize(1)))
                .andExpect(jsonPath("$.events[0].scoreContributions[0].contribution").value(0.72));
    }

    @Test
    void persistsScoreboardAnalysisWithoutChangingDetectorObservations() throws Exception {
        mediaAssetRepository.deleteAll();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID mediaAssetId = UUID.randomUUID();
        String sourceKey = mediaStorage.store(mediaAssetId, "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        MediaAsset asset = MediaAsset.restore(mediaAssetId, "LOCAL_UPLOAD", null, null,
                "scoreboard-match.mp4", ContentType.SPORTS, 90_000L, sourceKey,
                com.clipai.domain.media.MediaAssetStatus.COMPLETED, now, now);
        mediaAssetDomainRepository.save(asset);

        String analysisLocation = mockMvc.perform(post(
                        "/api/media-assets/" + mediaAssetId + "/scoreboard-analyses"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getHeader("Location");
        org.junit.jupiter.api.Assertions.assertNotNull(analysisLocation);
        UUID analysisId = UUID.fromString(analysisLocation.substring(analysisLocation.lastIndexOf('/') + 1));
        verify(scoreboardAnalysisTrigger).schedule(analysisId);

        List<ScoreboardObservation> observations = List.of(
                new ScoreboardObservation(UUID.randomUUID(), analysisId, 5_000, "OCR_OBSERVATION",
                        "BAR 0-0 RAY", 0.98, 0, 0, null, null, "{\"parsed\":true}"),
                new ScoreboardObservation(UUID.randomUUID(), analysisId, 10_000, "SCORE_STATE",
                        "BAR 0-0 RAY", 0.98, 0, 0, null, null, "{\"supportingReadings\":2}"));
        org.mockito.Mockito.when(scoreboardOcrService.analyze(any(), eq(analysisId)))
                .thenReturn(new ScoreboardOcrResult("PaddleOCR-test", 2, 2, 2, 1, 0, 0,
                        0, 120, observations));
        scoreboardAnalysisProcessor.process(analysisId);

        mockMvc.perform(get(analysisLocation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sampledFrameCount").value(2))
                .andExpect(jsonPath("$.processingErrorCount").value(0));
        mockMvc.perform(get(analysisLocation + "/observations")
                        .param("startTimeMs", "0").param("endTimeMs", "20000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].kind").value("SCORE_STATE"));
        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/observations")
                        .param("startTimeMs", "0").param("endTimeMs", "20000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertTrue(scoreboardAnalysisRepository
                .observations(mediaAssetId, analysisId, 0, 20_000, 10).stream()
                .allMatch(observation -> observation.analysisId().equals(analysisId)));
    }

    @Test
    void uploadsAndPersistsAFileBeforeReturningWithoutRunningTheWorker() throws Exception {
        mediaAssetRepository.deleteAll();
        byte[] videoBytes = new byte[] {1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile("file", "..\\user\\narration.mp4",
                "text/plain", videoBytes);

        String location = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/media-assets/upload")
                        .file(file)
                        .param("matchPart", "SECOND_HALF"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STORED"))
                .andReturn().getResponse().getHeader("Location");

        org.junit.jupiter.api.Assertions.assertNotNull(location);
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        var asset = mediaAssetDomainRepository.findById(id).orElseThrow();
        Path storedVideo = mediaStorage.resolve(asset.getLocalStoragePath());
        org.junit.jupiter.api.Assertions.assertArrayEquals(videoBytes, Files.readAllBytes(storedVideo));
        var sourceRange = mockMvc.perform(get("/api/media-assets/" + id + "/source")
                        .header("Range", "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Range", "bytes 1-3/5"))
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {2, 3, 4},
                sourceRange.getContentAsByteArray());
        mockMvc.perform(get("/api/media-assets/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("narration.mp4"))
                .andExpect(jsonPath("$.source").value("LOCAL_UPLOAD"))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.matchPart").value("SECOND_HALF"));
        verify(processingTrigger).schedule(eq(id));
    }

    @Test
    void persistsHumanReviewAndManualBoundariesWithoutChangingAutomaticDetection() throws Exception {
        mediaAssetRepository.deleteAll();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID mediaAssetId = UUID.randomUUID();
        String sourceKey = mediaStorage.store(mediaAssetId, "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        MediaAsset asset = MediaAsset.restore(mediaAssetId, "LOCAL_UPLOAD", null, null,
                "review-match.mp4", ContentType.SPORTS, 80_000L, sourceKey,
                com.clipai.domain.media.MediaAssetStatus.COMPLETED, now, now);
        mediaAssetDomainRepository.save(asset);
        CandidateEvent candidate = CandidateEvent.detected(mediaAssetId, 10_000, 50_000, 30_000,
                FootballEventType.GOAL, 0.92,
                java.util.List.of(new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                        FootballEventType.GOAL, 0.92, 30_000, "goal")), "goal context", now);
        candidateEventRepository.replaceForMediaAsset(mediaAssetId, java.util.List.of(candidate));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CONFIRMED",
                                  "manualStartTimeMs": 12000,
                                  "manualEndTimeMs": 48000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionStatus").value("DETECTED"))
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.automaticStartTimeMs").value(10_000))
                .andExpect(jsonPath("$.effectiveStartTimeMs").value(12_000))
                .andExpect(jsonPath("$.effectiveEndTimeMs").value(48_000));

        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualStartTimeMs").value(12_000))
                .andExpect(jsonPath("$.manualEndTimeMs").value(48_000));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CONFIRMED",
                                  "manualStartTimeMs": 48000,
                                  "manualEndTimeMs": 12000
                                }
                                """))
                .andExpect(status().isBadRequest());

        var unchanged = candidateEventRepository.findByIdAndMediaAssetId(candidate.id(), mediaAssetId).orElseThrow();
        assertEquals(com.clipai.domain.candidate.CandidateEventStatus.DETECTED, unchanged.status());
        assertEquals(10_000, unchanged.startTimeMs());
    }

    @Test
    void persistsHumanRejectionSeparatelyFromDetectionAndSavesMatchTimelineContext() throws Exception {
        mediaAssetRepository.deleteAll();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID mediaAssetId = UUID.randomUUID();
        String sourceKey = mediaStorage.store(mediaAssetId, "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        MediaAsset asset = MediaAsset.restore(mediaAssetId, "LOCAL_UPLOAD", null, null,
                "review-match.mp4", ContentType.SPORTS, 80_000L, sourceKey,
                com.clipai.domain.media.MediaAssetStatus.COMPLETED, now, now);
        mediaAssetDomainRepository.save(asset);
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                FootballEventType.GOAL, 0.9, 30_000, "goal");
        CandidateEvent candidate = CandidateEvent.detected(mediaAssetId, 10_000, 50_000,
                30_000, FootballEventType.GOAL, 0.9, java.util.List.of(signal), "goal", now);
        candidateEventRepository.replaceForMediaAsset(mediaAssetId, java.util.List.of(candidate));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REJECTED",
                                  "humanRejectionReason": "REPLAY",
                                  "note": "This is a replay of the earlier event."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionStatus").value("DETECTED"))
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.humanRejectionReason").value("REPLAY"));

        String contextUrl = "/api/media-assets/" + mediaAssetId + "/match-context";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(contextUrl + "/structure-markers/KICKOFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestampMs\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("KICKOFF"))
                .andExpect(jsonPath("$.timestampMs").value(5000));

        String scoreResponse = mockMvc.perform(post(contextUrl + "/score-transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timestampMs": 30000,
                                  "homeScore": 1,
                                  "awayScore": 0,
                                  "source": "MANUAL",
                                  "confidence": 1.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String transitionId = objectMapper.readTree(scoreResponse).get("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(contextUrl + "/score-transitions/" + transitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timestampMs": 31000,
                                  "homeScore": 1,
                                  "awayScore": 1,
                                  "source": "MANUAL",
                                  "confidence": 1.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayScore").value(1));

        mockMvc.perform(get(contextUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structureMarkers", hasSize(1)))
                .andExpect(jsonPath("$.structureMarkers[0].timestampMs").value(5000))
                .andExpect(jsonPath("$.scoreTransitions", hasSize(1)))
                .andExpect(jsonPath("$.scoreTransitions[0].timestampMs").value(31000))
                .andExpect(jsonPath("$.scoreTransitions[0].homeScore").value(1))
                .andExpect(jsonPath("$.scoreTransitions[0].awayScore").value(1));
    }

    @Test
    void generatesRejectedReviewClipOnDemandAndDeletesOnlyThatClipAfterHumanConfirmation() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID mediaAssetId = UUID.randomUUID();
        String sourceKey = mediaStorage.store(mediaAssetId, "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        MediaAsset asset = MediaAsset.restore(mediaAssetId, "LOCAL_UPLOAD", null, null,
                "rejected-review.mp4", ContentType.SPORTS, 80_000L, sourceKey,
                com.clipai.domain.media.MediaAssetStatus.COMPLETED, now, now);
        mediaAssetDomainRepository.save(asset);
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.REJECTION_REASON,
                FootballEventType.GOAL, 0.9, 30_000, "Insufficient scoring evidence");
        CandidateEvent candidate = CandidateEvent.rejected(mediaAssetId, 10_000, 50_000,
                30_000, FootballEventType.GOAL, 0.4, java.util.List.of(signal),
                "goal-like rejected candidate", now);
        candidateEventRepository.replaceForMediaAsset(mediaAssetId, java.util.List.of(candidate));
        var retainedClip = clipStorage.prepare(mediaAssetId, CandidateClipCategory.GOALS, candidate.id());
        Files.writeString(retainedClip.path(), "retained approved event clip");
        byte[] reviewVideoBytes = "temporary review clip".getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.doAnswer(invocation -> {
            Files.write(invocation.getArgument(1, Path.class), reviewVideoBytes);
            return null;
        }).when(videoClipper).cut(any(Path.class), any(Path.class), eq(15_000L), eq(45_000L));

        String clipPath = "/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id()
                + "/rejected-review-clip";
        mockMvc.perform(get(clipPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABSENT"));
        mockMvc.perform(post(clipPath))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        String completedClip = awaitRejectedReviewClip(clipPath);
        assertEquals("READY", objectMapper.readTree(completedClip).path("status").asText());
        assertEquals(15_000, objectMapper.readTree(completedClip).path("startTimeMs").asLong());
        assertEquals(45_000, objectMapper.readTree(completedClip).path("endTimeMs").asLong());
        mockMvc.perform(get(clipPath + "/video").header("Range", "bytes=0-7"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Range", "bytes 0-7/21"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().bytes(java.util.Arrays.copyOfRange(reviewVideoBytes, 0, 8)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REJECTED",
                                  "humanRejectionReason": "INSUFFICIENT_EVIDENCE",
                                  "note": "The source contains ordinary play."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionStatus").value("REJECTED"))
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.humanRejectionReason").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.reviewClipCleanupFailed").value(false));

        mockMvc.perform(get(clipPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        mockMvc.perform(get(clipPath + "/video"))
                .andExpect(status().isNotFound());
        assertTrue(Files.exists(mediaStorage.resolve(sourceKey)));
        assertEquals("retained approved event clip", Files.readString(retainedClip.path()));
        assertEquals(com.clipai.domain.candidate.CandidateEventStatus.REJECTED,
                candidateEventRepository.findByIdAndMediaAssetId(candidate.id(), mediaAssetId)
                        .orElseThrow().status());
        mockMvc.perform(get("/api/media-assets/" + mediaAssetId + "/candidates/" + candidate.id() + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("The source contains ordinary play."));
    }

    @Test
    void persistsAndExposesCandidateEventsAndCanScheduleDetection() throws Exception {
        mediaAssetRepository.deleteAll();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "match.mp4", ContentType.GAMING, now);
        String sourceKey = mediaStorage.store(asset.getId(), "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        asset.markStored(sourceKey, now.plusSeconds(1));
        asset.startProcessing(now.plusSeconds(2));
        asset.markCompleted(now.plusSeconds(3));
        mediaAssetDomainRepository.save(asset);
        Transcript transcript = Transcript.create(asset.getId(), "pt", now);
        transcript.startProcessing(now.plusSeconds(1));
        transcript.addSegment(TranscriptSegment.create(transcript.getId(), 0, 2_900, 3_800, "GOL!!!"));
        transcript.markCompleted("pt", now.plusSeconds(2));
        transcriptRepository.save(transcript);
        CandidateSignal signal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                FootballEventType.GOAL, 0.92, 3_000, "Football phrase match: gol");
        CandidateEvent detectedCandidate = CandidateEvent.detected(asset.getId(), 0, 10_000,
                3_000, FootballEventType.GOAL, 0.92, java.util.List.of(signal), "GOL!!!", now);
        UUID duplicateCandidateId = UUID.randomUUID();
        CandidateEvent candidate = detectedCandidate.withCanonicalDetails(0, 10_000,
                java.util.List.of(signal), "GOL!!!", java.util.List.of(detectedCandidate.id(), duplicateCandidateId),
                "Merged 2 GOAL detections with overlapping windows and shared transcript context");
        candidateEventRepository.replaceForMediaAsset(asset.getId(), java.util.List.of(candidate));

        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates")
                        .param("sort", "timestamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventType").value("GOAL"))
                .andExpect(jsonPath("$.events[0].startTimeMs").value(0))
                .andExpect(jsonPath("$.events[0].sourceCandidateIds", hasSize(2)))
                .andExpect(jsonPath("$.events[0].mergeReason").value(
                        "Merged 2 GOAL detections with overlapping windows and shared transcript context"))
                .andExpect(jsonPath("$.events[0].signals[0].confidence").value(0.92));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptContext").value("GOL!!!"))
                .andExpect(jsonPath("$.sourceCandidateIds", hasSize(2)))
                .andExpect(jsonPath("$.temporalSequence.assessment").value("CANONICAL_GOAL"))
                .andExpect(jsonPath("$.temporalSequence.events[0].sourceCandidateIds", hasSize(2)))
                .andExpect(jsonPath("$.temporalSequence.events[0].type").value("GOAL"))
                .andExpect(jsonPath("$.temporalSequence.events[0].sourceSignalType").value("TRANSCRIPT_KEYWORD"))
                .andExpect(jsonPath("$.temporalSequence.events[0].evidenceFamilies[0]").value("TRANSCRIPT"))
                .andExpect(jsonPath("$.mergeReason").value(
                        "Merged 2 GOAL detections with overlapping windows and shared transcript context"));
        String reviewUrl = "/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id() + "/review";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(reviewUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED","note":"Keep this reviewer decision"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.note").value("Keep this reviewer decision"));

        org.mockito.Mockito.doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "encoded clip");
            return null;
        }).when(videoClipper).cut(any(Path.class), any(Path.class), eq(0L), eq(10_000L));
        org.mockito.Mockito.doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "jpeg frame");
            return null;
        }).when(videoClipper).extractFrame(any(Path.class), any(Path.class),
                org.mockito.ArgumentMatchers.anyLong());
        mockMvc.perform(post("/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id() + "/clip"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("GOAL"))
                .andExpect(jsonPath("$.category").value("GOALS"))
                .andExpect(jsonPath("$.sourceCandidateIds", hasSize(2)))
                .andExpect(jsonPath("$.mergeReason").exists())
                .andExpect(jsonPath("$.storageKey")
                        .value("media/" + asset.getId() + "/clips/goals/" + candidate.id() + ".mp4"))
                .andExpect(jsonPath("$.reviewAssets.frames", hasSize(5)))
                .andExpect(jsonPath("$.reviewAssets.replays", hasSize(0)));
        var clipDownload = mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates/"
                        + candidate.id() + "/clip"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertEquals("video/mp4", clipDownload.getContentType());
        org.junit.jupiter.api.Assertions.assertArrayEquals("encoded clip".getBytes(StandardCharsets.UTF_8),
                clipDownload.getContentAsByteArray());
        var inlineClipRange = mockMvc.perform(get("/api/media-assets/" + asset.getId()
                        + "/candidates/" + candidate.id() + "/clip")
                        .param("inline", "true")
                        .header("Range", "bytes=1-5"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Range", "bytes 1-5/12"))
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertArrayEquals("ncode".getBytes(StandardCharsets.UTF_8),
                inlineClipRange.getContentAsByteArray());
        mockMvc.perform(get("/api/clips"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(candidate.id().toString())));
        var frameDownload = mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates/"
                        + candidate.id() + "/clip/frames/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertEquals("image/jpeg", frameDownload.getContentType());
        org.junit.jupiter.api.Assertions.assertArrayEquals("jpeg frame".getBytes(StandardCharsets.UTF_8),
                frameDownload.getContentAsByteArray());
        Path storedFrame = mediaStorage.resolve("media/" + asset.getId() + "/analysis/goal-review/"
                + candidate.id() + "/frames/frame-01.jpg");
        assertTrue(Files.isRegularFile(storedFrame));
        Path assetDirectory = storedFrame;
        for (int depth = 0; depth < 5; depth++) {
            assetDirectory = assetDirectory.getParent();
        }
        assertTrue(Files.notExists(assetDirectory.resolve("clips").resolve("goals")
                .resolve(candidate.id().toString()).resolve("frames").resolve("frame-01.jpg")));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id()
                        + "/clip/review-assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames", hasSize(5)))
                .andExpect(jsonPath("$.replays", hasSize(0)));
        verify(videoClipper).cut(any(Path.class), any(Path.class), eq(0L), eq(10_000L));

        mockMvc.perform(post("/api/media-assets/" + asset.getId() + "/candidates/detect"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mockMvc.perform(post("/api/media-assets/" + asset.getId() + "/candidates/detect"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Candidate detection is not ready or is already processing"));
        mockMvc.perform(get("/api/media-assets/" + asset.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateDetectionStatus").value("PROCESSING"));
        String runList = mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/detection-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].legacy").value(false))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].legacy").value(true))
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(runList).get(0).get("runId").asText();
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/detection-runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/detection-runs/" + runId + "/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/detection-runs/" + runId
                        + "/observations").param("startTimeMs", "0").param("endTimeMs", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/detection-runs/compare")
                        .param("leftRunId", "legacy").param("rightRunId", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leftRun.legacy").value(true))
                .andExpect(jsonPath("$.rightRun.runId").value(runId))
                .andExpect(jsonPath("$.onlyInLeft", hasSize(1)));
        assertEquals(candidate.id(), candidateEventRepository
                .findByIdAndMediaAssetId(candidate.id(), asset.getId()).orElseThrow().id());
        mockMvc.perform(get(reviewUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.note").value("Keep this reviewer decision"));
        assertTrue(Files.exists(mediaStorage.resolve(sourceKey)));
        assertEquals("encoded clip", Files.readString(mediaStorage.resolve(
                "media/" + asset.getId() + "/clips/goals/" + candidate.id() + ".mp4")));
        mockMvc.perform(get("/api/media-assets/" + asset.getId() + "/candidates/" + candidate.id() + "/clip"))
                .andExpect(status().isOk());
        verify(candidateDetectionTrigger).schedule(org.mockito.ArgumentMatchers.argThat(id -> !id.equals(asset.getId())));
    }

    @Test
    void generatesAndDownloadsDetectedCandidateClipsUsingStoredWindowsAndReusesThem() throws Exception {
        mediaAssetRepository.deleteAll();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        MediaAsset asset = MediaAsset.registerUpload("LOCAL_UPLOAD", "match.mp4", ContentType.SPORTS, now);
        String sourceKey = mediaStorage.store(asset.getId(), "mp4",
                new ByteArrayInputStream("source video".getBytes(StandardCharsets.UTF_8)));
        asset.markStored(sourceKey, now.plusSeconds(1));
        asset.startProcessing(now.plusSeconds(2));
        asset.markCompleted(now.plusSeconds(3));
        mediaAssetDomainRepository.save(asset);

        CandidateSignal goalSignal = new CandidateSignal(CandidateSignalType.TRANSCRIPT_KEYWORD,
                FootballEventType.GOAL, 0.92, 90_000, "Football phrase match: goal");
        CandidateEvent detected = CandidateEvent.detected(asset.getId(), 65_000, 125_000,
                90_000, FootballEventType.GOAL, 0.92, java.util.List.of(goalSignal), "goal context", now);
        CandidateEvent rejected = CandidateEvent.rejected(asset.getId(), 10_000, 50_000,
                30_000, FootballEventType.GOAL, 0.3,
                java.util.List.of(new CandidateSignal(CandidateSignalType.REJECTION_REASON,
                        FootballEventType.GOAL, 0.9, 30_000, "LOW_LIVE_EVENT_PROBABILITY")),
                "rejected replay hypothesis", now);
        candidateEventRepository.replaceForMediaAsset(asset.getId(), java.util.List.of(detected, rejected));

        byte[] mp4Bytes = new byte[] {0, 0, 0, 24, 102, 116, 121, 112};
        org.mockito.Mockito.doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.write(output, mp4Bytes);
            return null;
        }).when(videoClipper).cut(any(Path.class), any(Path.class), eq(65_000L), eq(125_000L));

        String path = "/api/media-assets/" + asset.getId() + "/candidates/clips";
        mockMvc.perform(post(path))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mediaAssetId").value(asset.getId().toString()));
        String completedJson = awaitClipBatch(path);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED",
                objectMapper.readTree(completedJson).path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                objectMapper.readTree(completedJson).path("totalCandidates").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("GENERATED",
                objectMapper.readTree(completedJson).path("clips").get(0).path("generationStatus").asText());
        org.junit.jupiter.api.Assertions.assertEquals(65_000,
                objectMapper.readTree(completedJson).path("clips").get(0).path("startTimeMs").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(125_000,
                objectMapper.readTree(completedJson).path("clips").get(0).path("endTimeMs").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(60_000,
                objectMapper.readTree(completedJson).path("clips").get(0).path("durationMs").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(detected.id().toString(),
                objectMapper.readTree(completedJson).path("clips").get(0).path("candidateId").asText());

        String clipStorageKey = objectMapper.readTree(completedJson)
                .path("clips").get(0).path("storageKey").asText();
        Path output = mediaStorage.resolve(clipStorageKey);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.size(output) > 0);
        String downloadUrl = objectMapper.readTree(completedJson)
                .path("clips").get(0).path("downloadUrl").asText();
        var clipDownload = mockMvc.perform(get(downloadUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertEquals("video/mp4", clipDownload.getContentType());
        assertArrayEquals(mp4Bytes, clipDownload.getContentAsByteArray());

        mockMvc.perform(post(path)).andExpect(status().isAccepted());
        String retryJson = awaitClipBatch(path);
        org.junit.jupiter.api.Assertions.assertEquals("REUSED",
                objectMapper.readTree(retryJson).path("clips").get(0).path("generationStatus").asText());
        verify(videoClipper, times(1)).cut(any(Path.class), any(Path.class), eq(65_000L), eq(125_000L));
    }

    @Test
    void documentsMultipartVideoUploadForSwagger() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/media-assets/upload'].post.requestBody.content"
                        + "['multipart/form-data'].schema.$ref")
                        .value("#/components/schemas/VideoUploadRequest"))
                .andExpect(jsonPath("$.components.schemas.VideoUploadRequest.properties.file.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/media-assets/upload'].post.responses.202").exists());
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/media-assets/{mediaAssetId}/candidates/{candidateId}/clip']"
                        + ".post.responses.201").exists())
                .andExpect(jsonPath("$.paths['/api/media-assets/{mediaAssetId}/candidates/{candidateId}/clip']"
                        + ".get.responses.200").exists());
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/media-assets/{mediaAssetId}/candidates/clips']"
                        + ".post.responses.202").exists())
                .andExpect(jsonPath("$.paths['/api/media-assets/{mediaAssetId}/candidates/clips']"
                        + ".get.responses.200").exists());
    }

    private String awaitClipBatch(String path) throws Exception {
        String response = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            response = mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String status = objectMapper.readTree(response).path("status").asText();
            if (!"PROCESSING".equals(status)) {
                return response;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Clip generation did not finish in time: " + response);
    }

    private String awaitRejectedReviewClip(String path) throws Exception {
        String response = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            response = mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String status = objectMapper.readTree(response).path("status").asText();
            if (!"PROCESSING".equals(status)) {
                return response;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Rejected review clip generation did not finish in time: " + response);
    }
}
