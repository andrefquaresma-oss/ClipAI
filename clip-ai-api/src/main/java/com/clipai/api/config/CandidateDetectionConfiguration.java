package com.clipai.api.config;

import com.clipai.application.candidate.AudioEnergyAnalyzer;
import com.clipai.application.candidate.AudioExcitementDetector;
import com.clipai.application.candidate.CandidateClipService;
import com.clipai.application.candidate.CandidateReviewService;
import com.clipai.application.candidate.CandidateReviewRepository;
import com.clipai.application.candidate.RejectedCandidateReviewClipService;
import com.clipai.application.candidate.ClipLibraryService;
import com.clipai.application.candidate.CandidateClipBatchService;
import com.clipai.application.candidate.CandidateDetectionClaim;
import com.clipai.application.candidate.CandidateDetectionProcessor;
import com.clipai.application.candidate.DetectionRunDescriptor;
import com.clipai.application.candidate.DetectionRunRepository;
import com.clipai.application.candidate.DetectionRunResultWriter;
import com.clipai.application.candidate.DetectionRunQueryService;
import com.clipai.application.candidate.DetectionRunCandidateMatcher;
import com.clipai.application.candidate.DetectionRunComparisonService;
import com.clipai.application.candidate.CandidateDetectionQualitySettings;
import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.candidate.CandidateDetectionSettings;
import com.clipai.application.candidate.CandidateEventClusterer;
import com.clipai.application.candidate.CandidateEventClusteringSettings;
import com.clipai.application.candidate.CandidateDetectionTrigger;
import com.clipai.application.candidate.CandidateContextSignalDetector;
import com.clipai.application.candidate.CandidateEventAssembler;
import com.clipai.application.candidate.CandidateGoalDiscovery;
import com.clipai.application.candidate.CandidateEventQueryService;
import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.CandidateObservationRepository;
import com.clipai.application.candidate.CandidateObservationQueryService;
import com.clipai.application.candidate.GoalReplayDetector;
import com.clipai.application.candidate.GoalReviewAssetService;
import com.clipai.application.candidate.GoalReviewSettings;
import com.clipai.application.matchcontext.MatchContextRepository;
import com.clipai.application.matchcontext.MatchContextService;
import com.clipai.application.ports.GoalReviewAssetStorage;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.ClipStorage;
import com.clipai.application.ports.RejectedReviewClipStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.application.candidate.TranscriptSignalDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
public class CandidateDetectionConfiguration {
    @Bean
    CandidateDetectionSettings candidateDetectionSettings(CandidateDetectionProperties properties) {
        return properties.toSettings();
    }

    @Bean
    DetectionRunDescriptor detectionRunDescriptor(
            CandidateDetectionProperties properties,
            ObjectMapper objectMapper,
            @Value("${clip-ai.candidate-detection.detector-version:clip-ai-candidate-detector-v0.3c.6}")
            String detectorVersion) {
        try {
            byte[] configuration = objectMapper.writeValueAsBytes(properties);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(configuration);
            StringBuilder hash = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hash.append(String.format("%02x", value));
            }
            return new DetectionRunDescriptor(detectorVersion, hash.toString());
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint candidate detection configuration", exception);
        }
    }

    @Bean
    CandidateDetectionQualitySettings candidateDetectionQualitySettings(CandidateDetectionProperties properties) {
        return properties.toQualitySettings();
    }

    @Bean
    CandidateEventClusteringSettings candidateEventClusteringSettings(CandidateDetectionProperties properties) {
        return properties.toEventClusteringSettings();
    }

    @Bean
    CandidateGoalDiscovery candidateGoalDiscovery(CandidateDetectionQualitySettings qualitySettings) {
        return new CandidateGoalDiscovery(qualitySettings);
    }

    @Bean
    CandidateEventClusterer candidateEventClusterer(CandidateEventClusteringSettings settings,
                                                     CandidateDetectionQualitySettings qualitySettings) {
        return new CandidateEventClusterer(settings, qualitySettings);
    }

    @Bean
    TranscriptSignalDetector transcriptSignalDetector(CandidateDetectionSettings settings) {
        return new TranscriptSignalDetector(settings);
    }

    @Bean
    AudioExcitementDetector audioExcitementDetector(CandidateDetectionSettings settings,
                                                   CandidateDetectionQualitySettings qualitySettings) {
        return new AudioExcitementDetector(settings, qualitySettings);
    }

    @Bean
    CandidateEventAssembler candidateEventAssembler(CandidateDetectionSettings settings,
                                                    CandidateDetectionQualitySettings qualitySettings) {
        return new CandidateEventAssembler(settings, qualitySettings);
    }

    @Bean
    CandidateContextSignalDetector candidateContextSignalDetector(
            CandidateDetectionQualitySettings qualitySettings) {
        return new CandidateContextSignalDetector(qualitySettings);
    }

    @Bean
    CandidateDetectionProcessor candidateDetectionProcessor(MediaAssetRepository mediaAssets,
                                                            TranscriptRepository transcripts,
                                                            DetectionRunRepository detectionRuns,
                                                            DetectionRunResultWriter resultWriter,
                                                            MediaStorage storage,
                                                            AudioEnergyAnalyzer audioEnergyAnalyzer,
                                                            TranscriptSignalDetector transcriptDetector,
                                                            AudioExcitementDetector audioDetector,
                                                            CandidateEventAssembler assembler,
                                                            CandidateDetectionSettings settings,
                                                            CandidateContextSignalDetector contextDetector,
                                                            CandidateGoalDiscovery goalDiscovery,
                                                            CandidateEventClusterer eventClusterer,
                                                            Clock clock) {
        return new CandidateDetectionProcessor(mediaAssets, transcripts, detectionRuns, resultWriter, storage,
                audioEnergyAnalyzer, transcriptDetector, audioDetector, assembler, settings, clock,
                contextDetector, goalDiscovery, eventClusterer);
    }

    @Bean
    CandidateDetectionScheduler candidateDetectionScheduler(MediaAssetRepository mediaAssets,
                                                            TranscriptRepository transcripts,
    DetectionRunRepository detectionRuns,
    DetectionRunDescriptor runDescriptor,
    CandidateDetectionTrigger trigger,
    Clock clock) {
        return new CandidateDetectionScheduler(mediaAssets, transcripts, detectionRuns,
                runDescriptor, trigger, clock);
    }

    @Bean
    CandidateEventQueryService candidateEventQueryService(MediaAssetRepository mediaAssets,
                                                          CandidateEventRepository candidates) {
        return new CandidateEventQueryService(mediaAssets, candidates);
    }

    @Bean
    CandidateObservationQueryService candidateObservationQueryService(
            MediaAssetRepository mediaAssets, CandidateObservationRepository observations) {
        return new CandidateObservationQueryService(mediaAssets, observations);
    }

    @Bean
    DetectionRunQueryService detectionRunQueryService(
            MediaAssetRepository mediaAssets, DetectionRunRepository runs,
            CandidateEventRepository candidates, CandidateObservationRepository observations,
            CandidateObservationQueryService observationQueries) {
        return new DetectionRunQueryService(mediaAssets, runs, candidates, observations, observationQueries);
    }

    @Bean
    DetectionRunCandidateMatcher detectionRunCandidateMatcher() {
        return new DetectionRunCandidateMatcher();
    }

    @Bean
    DetectionRunComparisonService detectionRunComparisonService(
            MediaAssetRepository mediaAssets, DetectionRunQueryService runs,
            DetectionRunCandidateMatcher matcher) {
        return new DetectionRunComparisonService(mediaAssets, runs, matcher);
    }

    @Bean
    CandidateClipService candidateClipService(MediaAssetRepository mediaAssets,
                                              CandidateEventRepository candidates,
                                              MediaStorage mediaStorage,
                                              ClipStorage clipStorage,
                                              VideoClipper videoClipper,
                                              CandidateReviewRepository reviews) {
        return new CandidateClipService(mediaAssets, candidates, mediaStorage, clipStorage,
                videoClipper, reviews);
    }

    @Bean
    CandidateReviewService candidateReviewService(MediaAssetRepository mediaAssets,
                                                  CandidateEventRepository candidates,
                                                  CandidateReviewRepository reviews,
                                                  Clock clock,
                                                  CandidateDetectionProperties properties) {
        return new CandidateReviewService(mediaAssets, candidates, reviews, clock,
                properties.toQualitySettings().maximumCandidateDurationMs());
    }

    @Bean
    RejectedCandidateReviewClipService rejectedCandidateReviewClipService(
            MediaAssetRepository mediaAssets,
            CandidateEventRepository candidates,
            CandidateReviewRepository reviews,
            MediaStorage mediaStorage,
            RejectedReviewClipStorage clips,
            VideoClipper videoClipper,
            @Qualifier("mediaProcessingExecutor") Executor executor,
            @Value("${clip-ai.rejected-review.clip-window-ms:30000}") long clipWindowMs) {
        return new RejectedCandidateReviewClipService(mediaAssets, candidates, reviews, mediaStorage,
                clips, videoClipper, executor, clipWindowMs);
    }

    @Bean
    MatchContextService matchContextService(MediaAssetRepository mediaAssets,
                                            MatchContextRepository matchContextRepository,
                                            Clock clock) {
        return new MatchContextService(mediaAssets, matchContextRepository, clock);
    }

    @Bean
    ClipLibraryService clipLibraryService(MediaAssetRepository mediaAssets,
                                          CandidateEventRepository candidates,
                                          CandidateReviewRepository reviews,
                                          ClipStorage storage) {
        return new ClipLibraryService(mediaAssets, candidates, reviews, storage);
    }

    @Bean
    CandidateClipBatchService candidateClipBatchService(MediaAssetRepository mediaAssets,
                                                        CandidateEventRepository candidates,
                                                        CandidateClipService clips,
                                                        Clock clock) {
        return new CandidateClipBatchService(mediaAssets, candidates, clips, clock);
    }

    @Bean
    GoalReviewSettings goalReviewSettings(
            @Value("${clip-ai.goal-review.frame-count:5}") int frameCount,
            @Value("${clip-ai.goal-review.frame-spacing-ms:5000}") long frameSpacingMs,
            @Value("${clip-ai.goal-review.replay-look-ahead-ms:300000}") long replayLookAheadMs,
            @Value("${clip-ai.goal-review.replay-pre-padding-ms:8000}") long replayPrePaddingMs,
            @Value("${clip-ai.goal-review.replay-post-padding-ms:25000}") long replayPostPaddingMs,
            @Value("${clip-ai.goal-review.maximum-replays:3}") int maximumReplays) {
        return new GoalReviewSettings(frameCount, frameSpacingMs, replayLookAheadMs,
                replayPrePaddingMs, replayPostPaddingMs, maximumReplays);
    }

    @Bean
    GoalReplayDetector goalReplayDetector(GoalReviewSettings settings) {
        return new GoalReplayDetector(settings);
    }

    @Bean
    GoalReviewAssetService goalReviewAssetService(MediaAssetRepository mediaAssets,
                                                  CandidateEventRepository candidates,
                                                  TranscriptRepository transcripts,
                                                  MediaStorage mediaStorage,
                                                  GoalReviewAssetStorage artifactStorage,
                                                  VideoClipper videoClipper,
                                                  GoalReplayDetector replayDetector,
                                                  GoalReviewSettings settings) {
        return new GoalReviewAssetService(mediaAssets, candidates, transcripts, mediaStorage,
                artifactStorage, videoClipper, replayDetector, settings);
    }
}
