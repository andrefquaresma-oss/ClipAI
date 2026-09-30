package com.clipai.application.candidate;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.MediaAssetRepository;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.GoalReviewAssetStorage;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.VideoClipper;
import com.clipai.application.transcript.TranscriptRepository;
import com.clipai.domain.candidate.CandidateEvent;
import com.clipai.domain.candidate.FootballEventType;
import com.clipai.domain.media.MediaAsset;
import com.clipai.domain.media.MediaAssetStatus;
import com.clipai.domain.transcript.Transcript;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GoalReviewAssetService {
    private final MediaAssetRepository mediaAssets;
    private final CandidateEventRepository candidates;
    private final TranscriptRepository transcripts;
    private final MediaStorage mediaStorage;
    private final GoalReviewAssetStorage artifactStorage;
    private final VideoClipper videoClipper;
    private final GoalReplayDetector replayDetector;
    private final GoalReviewSettings settings;

    public GoalReviewAssetService(MediaAssetRepository mediaAssets, CandidateEventRepository candidates,
                                  TranscriptRepository transcripts, MediaStorage mediaStorage,
                                  GoalReviewAssetStorage artifactStorage, VideoClipper videoClipper,
                                  GoalReplayDetector replayDetector, GoalReviewSettings settings) {
        this.mediaAssets = mediaAssets;
        this.candidates = candidates;
        this.transcripts = transcripts;
        this.mediaStorage = mediaStorage;
        this.artifactStorage = artifactStorage;
        this.videoClipper = videoClipper;
        this.replayDetector = replayDetector;
        this.settings = settings;
    }

    public GoalReviewAssets export(UUID mediaAssetId, UUID candidateId) {
        ReviewTarget target = loadTarget(mediaAssetId, candidateId);
        Path source = sourcePath(target.asset());
        List<GoalReviewFrame> frames = exportFrames(target, source);
        List<GoalReviewReplay> replays = exportReplays(target, source);
        return new GoalReviewAssets(frames, replays);
    }

    public GoalReviewAssets find(UUID mediaAssetId, UUID candidateId) {
        ReviewTarget target = loadTarget(mediaAssetId, candidateId);
        List<GoalReviewFrame> frames = findFrames(target);
        List<GoalReviewReplay> replays = findReplays(target);
        return new GoalReviewAssets(frames, replays);
    }

    public Path findFrame(UUID mediaAssetId, UUID candidateId, int frameNumber) {
        loadTarget(mediaAssetId, candidateId);
        return artifactStorage.findFrame(mediaAssetId, candidateId, frameNumber)
                .map(location -> mediaStorage.resolve(location.storageKey()))
                .orElseThrow(() -> new GoalReviewAssetNotFoundException(
                        "Goal build-up frame not found: " + frameNumber));
    }

    public Path findReplay(UUID mediaAssetId, UUID candidateId, int replayNumber) {
        loadTarget(mediaAssetId, candidateId);
        return artifactStorage.findReplay(mediaAssetId, candidateId, replayNumber)
                .map(location -> mediaStorage.resolve(location.storageKey()))
                .orElseThrow(() -> new GoalReviewAssetNotFoundException(
                        "Goal replay clip not found: " + replayNumber));
    }

    private List<GoalReviewFrame> exportFrames(ReviewTarget target, Path source) {
        List<GoalReviewFrame> frames = new ArrayList<>();
        List<Long> frameTimes = frameTimes(target.goal().triggerTimestampMs());
        for (int index = 0; index < frameTimes.size(); index++) {
            int frameNumber = index + 1;
            var existing = artifactStorage.findFrame(target.asset().getId(), target.goal().id(), frameNumber);
            ClipStorageLocation location = existing.orElseGet(() -> artifactStorage.prepareFrame(
                    target.asset().getId(), target.goal().id(), frameNumber));
            if (existing.isEmpty()) {
                videoClipper.extractFrame(source, location.path(), frameTimes.get(index));
                location = artifactStorage.findFrame(target.asset().getId(), target.goal().id(), frameNumber)
                        .orElseThrow(() -> new IllegalStateException(
                                "Frame extractor did not produce the requested image"));
            }
            frames.add(new GoalReviewFrame(frameNumber, frameTimes.get(index), location.storageKey()));
        }
        return List.copyOf(frames);
    }

    private List<GoalReviewReplay> exportReplays(ReviewTarget target, Path source) {
        List<GoalReplayCue> cues = replayDetector.detect(target.transcript(), target.goal());
        List<GoalReviewReplay> replays = new ArrayList<>();
        for (int index = 0; index < cues.size(); index++) {
            int replayNumber = index + 1;
            GoalReplayCue cue = cues.get(index);
            var existing = artifactStorage.findReplay(target.asset().getId(), target.goal().id(), replayNumber);
            ClipStorageLocation location = existing.orElseGet(() -> artifactStorage.prepareReplay(
                    target.asset().getId(), target.goal().id(), replayNumber));
            if (existing.isEmpty()) {
                videoClipper.cut(source, location.path(), cue.startTimeMs(), cue.endTimeMs());
                location = artifactStorage.findReplay(target.asset().getId(), target.goal().id(), replayNumber)
                        .orElseThrow(() -> new IllegalStateException(
                                "Video clipper did not produce the requested replay"));
            }
            replays.add(new GoalReviewReplay(replayNumber, cue.startTimeMs(), cue.endTimeMs(),
                    cue.cueTimeMs(), cue.transcriptCue(), location.storageKey()));
        }
        return List.copyOf(replays);
    }

    private List<GoalReviewFrame> findFrames(ReviewTarget target) {
        List<Long> frameTimes = frameTimes(target.goal().triggerTimestampMs());
        List<GoalReviewFrame> frames = new ArrayList<>();
        for (int index = 0; index < frameTimes.size(); index++) {
            int frameNumber = index + 1;
            long timestampMs = frameTimes.get(index);
            artifactStorage.findFrame(target.asset().getId(), target.goal().id(), frameNumber)
                    .ifPresent(location -> frames.add(new GoalReviewFrame(frameNumber,
                            timestampMs, location.storageKey())));
        }
        return List.copyOf(frames);
    }

    private List<GoalReviewReplay> findReplays(ReviewTarget target) {
        List<GoalReplayCue> cues = replayDetector.detect(target.transcript(), target.goal());
        List<GoalReviewReplay> replays = new ArrayList<>();
        for (int index = 0; index < cues.size(); index++) {
            int replayNumber = index + 1;
            GoalReplayCue cue = cues.get(index);
            artifactStorage.findReplay(target.asset().getId(), target.goal().id(), replayNumber)
                    .ifPresent(location -> replays.add(new GoalReviewReplay(replayNumber, cue.startTimeMs(),
                            cue.endTimeMs(), cue.cueTimeMs(), cue.transcriptCue(), location.storageKey())));
        }
        return List.copyOf(replays);
    }

    private List<Long> frameTimes(long triggerTimestampMs) {
        if (triggerTimestampMs == 0) {
            return List.of();
        }
        long oldestTimestamp = Math.max(0, triggerTimestampMs
                - (long) settings.frameCount() * settings.frameSpacingMs());
        long availableWindow = triggerTimestampMs - oldestTimestamp;
        List<Long> timestamps = new ArrayList<>(settings.frameCount());
        boolean fullSpacingAvailable = availableWindow >= (long) settings.frameCount() * settings.frameSpacingMs();
        for (int index = 0; index < settings.frameCount(); index++) {
            long timestamp = fullSpacingAvailable
                    ? oldestTimestamp + (long) index * settings.frameSpacingMs()
                    : oldestTimestamp + availableWindow * (index + 1) / (settings.frameCount() + 1L);
            timestamps.add(timestamp);
        }
        return List.copyOf(timestamps);
    }

    private ReviewTarget loadTarget(UUID mediaAssetId, UUID candidateId) {
        MediaAsset asset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(mediaAssetId));
        if (asset.getStatus() != MediaAssetStatus.COMPLETED) {
            throw new CandidateClipExportConflictException("Media asset must be completed before review export");
        }
        CandidateEvent goal = candidates.findByIdAndMediaAssetId(candidateId, mediaAssetId)
                .orElseThrow(() -> new CandidateEventNotFoundException(candidateId, mediaAssetId));
        if (goal.eventType() != FootballEventType.GOAL) {
            throw new GoalReviewAssetUnsupportedException(
                    "Review images and replay export only support goal candidates");
        }
        Transcript transcript = transcripts.findByMediaAssetId(mediaAssetId)
                .orElseThrow(() -> new CandidateClipExportConflictException("Transcript is unavailable"));
        return new ReviewTarget(asset, goal, transcript);
    }

    private Path sourcePath(MediaAsset asset) {
        String sourceKey = asset.getLocalStoragePath();
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new CandidateClipExportConflictException("Source video is unavailable for review export");
        }
        return mediaStorage.resolve(sourceKey);
    }

    private record ReviewTarget(MediaAsset asset, CandidateEvent goal, Transcript transcript) {
    }
}
