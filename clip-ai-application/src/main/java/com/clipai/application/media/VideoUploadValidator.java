package com.clipai.application.media;

import java.util.Set;

public final class VideoUploadValidator {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp4", "mkv", "webm", "mov", "avi");
    private final long maxFileSizeBytes;

    public VideoUploadValidator(long maxFileSizeBytes) {
        if (maxFileSizeBytes < 1) {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public String validateAndGetExtension(String originalFilename, long fileSize) {
        if (fileSize < 1) {
            throw new InvalidVideoUploadException("Uploaded file must not be empty");
        }
        if (fileSize > maxFileSizeBytes) {
            throw new UploadTooLargeException();
        }
        String filename = safeFilename(originalFilename);
        int dot = filename.lastIndexOf('.');
        if (dot < 1 || dot == filename.length() - 1) {
            throw new InvalidVideoUploadException("Unsupported video file type");
        }
        String extension = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new InvalidVideoUploadException("Unsupported video file type");
        }
        return extension;
    }

    public String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidVideoUploadException("Uploaded file must have a filename");
        }
        String normalized = originalFilename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
            throw new InvalidVideoUploadException("Uploaded file must have a valid filename");
        }
        return basename.length() > 500 ? basename.substring(basename.length() - 500) : basename;
    }
}
