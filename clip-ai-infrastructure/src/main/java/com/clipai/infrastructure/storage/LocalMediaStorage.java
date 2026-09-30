package com.clipai.infrastructure.storage;

import com.clipai.application.candidate.CandidateClipCategory;
import com.clipai.application.ports.ClipStorage;
import com.clipai.application.ports.ClipStorageLocation;
import com.clipai.application.ports.GoalReviewAssetStorage;
import com.clipai.application.ports.MediaStorage;
import com.clipai.application.ports.RejectedReviewClipStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class LocalMediaStorage implements MediaStorage, ClipStorage, GoalReviewAssetStorage,
        RejectedReviewClipStorage {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp4", "mkv", "webm", "mov", "avi");
    private final Path root;
    private final Path mediaRoot;

    public LocalMediaStorage(@Value("${media.storage.root:./data/media}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.mediaRoot = this.root.resolve("media").normalize();
    }

    @Override
    public String store(UUID mediaAssetId, String extension, InputStream content) {
        if (mediaAssetId == null || extension == null || !SUPPORTED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new StorageException("Invalid media storage key");
        }
        Path directory = mediaRoot.resolve(mediaAssetId.toString()).normalize();
        if (!directory.startsWith(mediaRoot)) {
            throw new StorageException("Invalid media storage key");
        }
        Path source = directory.resolve("source." + extension.toLowerCase()).normalize();
        try {
            Files.createDirectories(mediaRoot);
            Files.createDirectory(directory);
            try (OutputStream output = Files.newOutputStream(source,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                content.transferTo(output);
            }
            return root.relativize(source).toString().replace('\\', '/');
        } catch (FileAlreadyExistsException exception) {
            throw new StorageException("Media asset storage location already exists", exception);
        } catch (IOException exception) {
            try {
                removePartialUpload(source, directory);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new StorageException("Unable to store uploaded media", exception);
        }
    }

    @Override
    public Path resolve(String storageKey) {
        Path file = resolveKey(storageKey);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new StorageException("Stored media file is missing or unreadable");
        }
        return file;
    }

    @Override
    public Path audioPath(String sourceStorageKey) {
        Path source = resolveKey(sourceStorageKey);
        Path directory = source.getParent();
        if (directory == null || !directory.startsWith(mediaRoot)) {
            throw new StorageException("Invalid media storage key");
        }
        return directory.resolve("audio.wav");
    }

    @Override
    public ClipStorageLocation prepare(UUID mediaAssetId, CandidateClipCategory category, UUID candidateId) {
        Path output = clipPath(mediaAssetId, category, candidateId);
        try {
            Files.createDirectories(output.getParent());
            return clipLocation(output);
        } catch (IOException exception) {
            throw new StorageException("Unable to prepare candidate clip storage", exception);
        }
    }

    @Override
    public Optional<ClipStorageLocation> find(UUID mediaAssetId, CandidateClipCategory category,
                                               UUID candidateId) {
        Path output = clipPath(mediaAssetId, category, candidateId);
        return Files.isRegularFile(output) && Files.isReadable(output)
                ? Optional.of(clipLocation(output)) : Optional.empty();
    }

    @Override
    public ClipStorageLocation prepare(UUID mediaAssetId, UUID candidateId) {
        Path output = rejectedReviewClipPath(mediaAssetId, candidateId);
        try {
            Files.createDirectories(output.getParent());
            return clipLocation(output);
        } catch (IOException exception) {
            throw new StorageException("Unable to prepare rejected-candidate review clip", exception);
        }
    }

    @Override
    public Optional<ClipStorageLocation> find(UUID mediaAssetId, UUID candidateId) {
        return findFile(rejectedReviewClipPath(mediaAssetId, candidateId));
    }

    @Override
    public void delete(UUID mediaAssetId, UUID candidateId) {
        try {
            Files.deleteIfExists(rejectedReviewClipPath(mediaAssetId, candidateId));
        } catch (IOException exception) {
            throw new StorageException("Unable to remove rejected-candidate review clip", exception);
        }
    }

    @Override
    public void delete(ClipStorageLocation location) {
        Path output = clipPathFromLocation(location);
        try {
            Files.deleteIfExists(output);
        } catch (IOException exception) {
            throw new StorageException("Unable to remove incomplete candidate clip", exception);
        }
    }

    @Override
    public ClipStorageLocation prepareFrame(UUID mediaAssetId, UUID candidateId, int frameNumber) {
        Path output = analysisFramePath(mediaAssetId, candidateId, frameNumber);
        try {
            Files.createDirectories(output.getParent());
            return clipLocation(output);
        } catch (IOException exception) {
            throw new StorageException("Unable to prepare goal frame storage", exception);
        }
    }

    @Override
    public Optional<ClipStorageLocation> findFrame(UUID mediaAssetId, UUID candidateId, int frameNumber) {
        Optional<ClipStorageLocation> current = findFile(analysisFramePath(mediaAssetId, candidateId, frameNumber));
        return current.isPresent() ? current
                : findReviewAsset(mediaAssetId, candidateId, "frames", "frame", frameNumber, "jpg", 12);
    }

    @Override
    public ClipStorageLocation prepareReplay(UUID mediaAssetId, UUID candidateId, int replayNumber) {
        Path output = reviewAssetPath(mediaAssetId, candidateId, "replays", "replay", replayNumber, "mp4", 5);
        try {
            Files.createDirectories(output.getParent());
            return clipLocation(output);
        } catch (IOException exception) {
            throw new StorageException("Unable to prepare goal replay storage", exception);
        }
    }

    @Override
    public Optional<ClipStorageLocation> findReplay(UUID mediaAssetId, UUID candidateId, int replayNumber) {
        return findReviewAsset(mediaAssetId, candidateId, "replays", "replay", replayNumber, "mp4", 5);
    }

    @Override
    public void delete(String storageKey) {
        Path source = resolveKey(storageKey);
        Path directory = source.getParent();
        if (directory == null || !directory.startsWith(mediaRoot)) {
            throw new StorageException("Invalid media storage key");
        }
        try {
            if (Files.exists(directory)) {
                try (Stream<Path> paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to clean up stored media", exception);
        }
    }

    private Path resolveKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageException("Invalid media storage key");
        }
        try {
            Path key = Path.of(storageKey.replace('\\', '/'));
            if (key.isAbsolute()) {
                throw new StorageException("Invalid media storage key");
            }
            Path resolved = root.resolve(key).normalize();
            if (!resolved.startsWith(mediaRoot) || resolved.equals(mediaRoot)) {
                throw new StorageException("Invalid media storage key");
            }
            return resolved;
        } catch (java.nio.file.InvalidPathException exception) {
            throw new StorageException("Invalid media storage key", exception);
        }
    }

    private Path clipPath(UUID mediaAssetId, CandidateClipCategory category, UUID candidateId) {
        if (mediaAssetId == null || category == null || candidateId == null) {
            throw new StorageException("Invalid candidate clip storage key");
        }
        Path assetDirectory = mediaRoot.resolve(mediaAssetId.toString()).normalize();
        Path clip = assetDirectory.resolve("clips").resolve(category.folderName())
                .resolve(candidateId + ".mp4").normalize();
        if (!assetDirectory.startsWith(mediaRoot) || !clip.startsWith(assetDirectory)) {
            throw new StorageException("Invalid candidate clip storage key");
        }
        return clip;
    }

    private Path rejectedReviewClipPath(UUID mediaAssetId, UUID candidateId) {
        if (mediaAssetId == null || candidateId == null) {
            throw new StorageException("Invalid rejected-candidate review clip storage key");
        }
        Path assetDirectory = mediaRoot.resolve(mediaAssetId.toString()).normalize();
        Path clip = assetDirectory.resolve("review-clips").resolve("rejected")
                .resolve(candidateId + ".mp4").normalize();
        if (!assetDirectory.startsWith(mediaRoot) || !clip.startsWith(assetDirectory)
                || clip.equals(assetDirectory)) {
            throw new StorageException("Invalid rejected-candidate review clip storage key");
        }
        return clip;
    }

    private Path reviewAssetPath(UUID mediaAssetId, UUID candidateId, String folder, String prefix,
                                 int number, String extension, int maximumNumber) {
        if (mediaAssetId == null || candidateId == null || number < 1 || number > maximumNumber) {
            throw new StorageException("Invalid goal review asset storage key");
        }
        Path assetDirectory = mediaRoot.resolve(mediaAssetId.toString()).normalize();
        Path output = assetDirectory.resolve("clips").resolve("goals").resolve(candidateId.toString())
                .resolve(folder).resolve(String.format(java.util.Locale.ROOT, "%s-%02d.%s",
                        prefix, number, extension)).normalize();
        if (!assetDirectory.startsWith(mediaRoot) || !output.startsWith(assetDirectory)) {
            throw new StorageException("Invalid goal review asset storage key");
        }
        return output;
    }

    private Path analysisFramePath(UUID mediaAssetId, UUID candidateId, int frameNumber) {
        if (mediaAssetId == null || candidateId == null || frameNumber < 1 || frameNumber > 12) {
            throw new StorageException("Invalid goal review asset storage key");
        }
        Path assetDirectory = mediaRoot.resolve(mediaAssetId.toString()).normalize();
        Path output = assetDirectory.resolve("analysis").resolve("goal-review")
                .resolve(candidateId.toString()).resolve("frames")
                .resolve(String.format(java.util.Locale.ROOT, "frame-%02d.jpg", frameNumber)).normalize();
        if (!assetDirectory.startsWith(mediaRoot) || !output.startsWith(assetDirectory)) {
            throw new StorageException("Invalid goal review asset storage key");
        }
        return output;
    }

    private Optional<ClipStorageLocation> findReviewAsset(UUID mediaAssetId, UUID candidateId,
                                                          String folder, String prefix, int number,
                                                          String extension, int maximumNumber) {
        Path path = reviewAssetPath(mediaAssetId, candidateId, folder, prefix, number, extension, maximumNumber);
        return Files.isRegularFile(path) && Files.isReadable(path)
                ? Optional.of(clipLocation(path)) : Optional.empty();
    }

    private Optional<ClipStorageLocation> findFile(Path path) {
        return Files.isRegularFile(path) && Files.isReadable(path)
                ? Optional.of(clipLocation(path)) : Optional.empty();
    }

    private ClipStorageLocation clipLocation(Path path) {
        return new ClipStorageLocation(root.relativize(path).toString().replace('\\', '/'), path);
    }

    private Path clipPathFromLocation(ClipStorageLocation location) {
        if (location == null) {
            throw new StorageException("Invalid candidate clip storage location");
        }
        Path path = location.path().toAbsolutePath().normalize();
        if (!path.startsWith(mediaRoot)) {
            throw new StorageException("Invalid candidate clip storage location");
        }
        Path relative = mediaRoot.relativize(path);
        if (relative.getNameCount() != 4 || !relative.getName(1).toString().equals("clips")
                || !location.storageKey().equals(root.relativize(path).toString().replace('\\', '/'))) {
            throw new StorageException("Invalid candidate clip storage location");
        }
        return path;
    }

    private static void removePartialUpload(Path source, Path directory) throws IOException {
        Files.deleteIfExists(source);
        Files.deleteIfExists(directory);
    }
}
