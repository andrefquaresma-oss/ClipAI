package com.clipai.api.config;

import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.media.MediaProcessingService;
import com.clipai.application.media.MediaProcessingControlService;
import com.clipai.application.media.ProcessingStageRepository;
import com.clipai.application.media.UploadMediaAssetService;
import com.clipai.application.candidate.CandidateDetectionScheduler;
import com.clipai.application.candidate.CandidateEventRepository;
import com.clipai.application.candidate.ManualCandidateEventService;
import com.clipai.application.candidate.CandidateReviewRepository;
import com.clipai.application.groundtruth.GroundTruthRepository;
import com.clipai.application.groundtruth.GroundTruthService;
import com.clipai.application.analytics.ControlCenterAnalyticsRepository;
import com.clipai.application.analytics.ControlCenterAnalyticsService;
import com.clipai.application.candidate.ClipLibraryService;
import com.clipai.application.scoreboard.ScoreboardAnalysisProcessor;
import com.clipai.application.scoreboard.ScoreboardAnalysisRepository;
import com.clipai.application.scoreboard.ScoreboardAnalysisService;
import com.clipai.application.scoreboard.ScoreboardAnalysisTrigger;
import com.clipai.application.scoreboard.ScoreboardOcrService;
import com.clipai.application.ports.MediaProcessor;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.MediaProcessingTrigger;
import com.clipai.application.ports.TranscriptionService;
import com.clipai.application.transcript.TranscriptRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
@Configuration
@EnableAsync
public class ProcessingConfiguration {
    @Bean
    ScoreboardAnalysisService scoreboardAnalysisService(MediaAssetRepository mediaAssets,
                                                        ScoreboardAnalysisRepository analyses,
                                                        ScoreboardAnalysisTrigger trigger,
                                                        Clock clock,
                                                        ScoreboardOcrProperties properties) {
        return new ScoreboardAnalysisService(mediaAssets, analyses, trigger, clock,
                properties.modelVersion(), properties.enabled());
    }

    @Bean
    ScoreboardAnalysisProcessor scoreboardAnalysisProcessor(ScoreboardAnalysisRepository analyses,
                                                            MediaAssetRepository mediaAssets,
                                                            MediaStorage storage,
                                                            ScoreboardOcrService ocr,
                                                            Clock clock) {
        return new ScoreboardAnalysisProcessor(analyses, mediaAssets, storage, ocr, clock);
    }

    @Bean(name = "mediaProcessingExecutor")
    ThreadPoolTaskExecutor mediaProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("media-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    MediaProcessingService mediaProcessingService(MediaAssetRepository mediaAssets,
                                                  TranscriptRepository transcripts,
                                                  MediaStorage storage,
                                                  MediaProcessor mediaProcessor,
                                                  TranscriptionService transcriptionService,
                                                  CandidateDetectionScheduler candidateDetectionScheduler,
                                                  ProcessingStageRepository stages,
                                                  Clock clock) {
        return new MediaProcessingService(mediaAssets, transcripts, storage, mediaProcessor,
                transcriptionService, candidateDetectionScheduler, stages, clock);
    }

    @Bean
    MediaProcessingControlService mediaProcessingControlService(MediaAssetRepository mediaAssets,
                                                                TranscriptRepository transcripts,
                                                                ProcessingStageRepository stages,
                                                                MediaProcessingTrigger trigger,
                                                                Clock clock) {
        return new MediaProcessingControlService(mediaAssets, transcripts, stages, trigger, clock);
    }

    @Bean
    ManualCandidateEventService manualCandidateEventService(MediaAssetRepository mediaAssets,
                                                            CandidateEventRepository candidates,
                                                            Clock clock,
                                                            CandidateDetectionProperties properties) {
        return new ManualCandidateEventService(mediaAssets, candidates, clock,
                properties.toQualitySettings().maximumCandidateDurationMs());
    }

    @Bean
    GroundTruthService groundTruthService(MediaAssetRepository mediaAssets,
                                          CandidateEventRepository candidates,
                                          CandidateReviewRepository reviews,
                                          GroundTruthRepository groundTruth,
                                          Clock clock) {
        return new GroundTruthService(mediaAssets, candidates, reviews, groundTruth, clock);
    }

    @Bean
    ControlCenterAnalyticsService controlCenterAnalyticsService(ControlCenterAnalyticsRepository analytics,
                                                                ClipLibraryService clips) {
        return new ControlCenterAnalyticsService(analytics, clips);
    }

    @Bean
    UploadMediaAssetService uploadMediaAssetService(MediaAssetRepository mediaAssets,
                                                    MediaStorage storage,
                                                    MediaProcessingTrigger processingTrigger,
                                                    UploadProperties uploadProperties,
                                                    Clock clock) {
        return new UploadMediaAssetService(mediaAssets, storage, processingTrigger,
                uploadProperties.maxFileSize().toBytes(), clock);
    }
}
