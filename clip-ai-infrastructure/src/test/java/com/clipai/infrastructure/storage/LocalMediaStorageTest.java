package com.clipai.infrastructure.storage;

import com.clipai.application.candidate.CandidateClipCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMediaStorageTest {
    @TempDir
    Path root;

    @Test
    void storesEachAssetAtItsOwnDeterministicKeyAndDoesNotOverwrite() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(root);
        UUID id = UUID.randomUUID();
        String key = storage.store(id, "mp4",
                new ByteArrayInputStream("video".getBytes(StandardCharsets.UTF_8)));

        assertEquals("media/" + id + "/source.mp4", key);
        assertEquals("video", Files.readString(storage.resolve(key)));
        Path audioPath = storage.audioPath(key);
        assertEquals(root.resolve("media").resolve(id.toString()).resolve("audio.wav"), audioPath);
        assertThrows(StorageException.class, () -> storage.store(id, "mp4",
                new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8))));
        assertEquals("video", Files.readString(storage.resolve(key)));
    }

    @Test
    void refusesStorageKeysOutsideMediaRoot() {
        LocalMediaStorage storage = new LocalMediaStorage(root);

        assertThrows(StorageException.class, () -> storage.resolve("media/../../outside.wav"));
        assertThrows(StorageException.class, () -> storage.resolve(root.resolve("secret.mp4").toString()));
    }

    @Test
    void storesCandidateClipsInEventFoldersAndRemovesOnlyIncompleteClip() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(root);
        UUID assetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();

        var location = storage.prepare(assetId, CandidateClipCategory.GOALS, candidateId);

        assertEquals("media/" + assetId + "/clips/goals/" + candidateId + ".mp4", location.storageKey());
        assertTrue(location.path().getParent().endsWith(Path.of("clips", "goals")));
        assertTrue(storage.find(assetId, CandidateClipCategory.GOALS, candidateId).isEmpty());
        Files.writeString(location.path(), "partial");
        assertEquals(location, storage.find(assetId, CandidateClipCategory.GOALS, candidateId).orElseThrow());

        storage.delete(location);

        assertFalse(Files.exists(location.path()));
        assertTrue(Files.isDirectory(root.resolve("media").resolve(assetId.toString())));
    }

    @Test
    void rejectedReviewCleanupDoesNotDeleteSourceOrRetainedEventClips() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(root);
        UUID assetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String sourceKey = storage.store(assetId, "mp4",
                new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)));
        var retainedClip = storage.prepare(assetId, CandidateClipCategory.GOALS, candidateId);
        Files.writeString(retainedClip.path(), "retained event clip");
        var reviewClip = storage.prepare(assetId, candidateId);
        Files.writeString(reviewClip.path(), "temporary review clip");

        storage.delete(assetId, candidateId);

        assertFalse(Files.exists(reviewClip.path()));
        assertEquals("source", Files.readString(storage.resolve(sourceKey)));
        assertEquals("retained event clip", Files.readString(retainedClip.path()));
    }

    @Test
    void storesGoalFramesInSeparateAnalysisStorageAndReplayClipsInCandidateScopedFolders() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(root);
        UUID assetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        var frame = storage.prepareFrame(assetId, candidateId, 1);
        var replay = storage.prepareReplay(assetId, candidateId, 1);

        assertEquals("media/" + assetId + "/analysis/goal-review/" + candidateId
                + "/frames/frame-01.jpg", frame.storageKey());
        assertEquals("media/" + assetId + "/clips/goals/" + candidateId
                + "/replays/replay-01.mp4", replay.storageKey());
        assertTrue(storage.findFrame(assetId, candidateId, 1).isEmpty());
        assertTrue(storage.findReplay(assetId, candidateId, 1).isEmpty());
        Files.writeString(frame.path(), "jpeg");
        Files.writeString(replay.path(), "mp4");

        assertEquals(frame, storage.findFrame(assetId, candidateId, 1).orElseThrow());
        assertEquals(replay, storage.findReplay(assetId, candidateId, 1).orElseThrow());
        assertFalse(Files.exists(root.resolve("media").resolve(assetId.toString())
                .resolve("clips").resolve("goals").resolve(candidateId.toString()).resolve("frames")));
        assertTrue(Files.exists(frame.path().getParent()));
        assertThrows(StorageException.class, () -> storage.prepareFrame(assetId, candidateId, 13));
        assertThrows(StorageException.class, () -> storage.prepareReplay(assetId, candidateId, 6));
    }

    @Test
    void canStillFindPreviouslyPersistedGoalFramesInTheLegacyClipFolder() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(root);
        UUID assetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Path legacyFrame = root.resolve("media").resolve(assetId.toString()).resolve("clips")
                .resolve("goals").resolve(candidateId.toString()).resolve("frames").resolve("frame-01.jpg");
        Files.createDirectories(legacyFrame.getParent());
        Files.writeString(legacyFrame, "legacy jpeg");

        assertEquals(legacyFrame, storage.findFrame(assetId, candidateId, 1).orElseThrow().path());
    }
}
