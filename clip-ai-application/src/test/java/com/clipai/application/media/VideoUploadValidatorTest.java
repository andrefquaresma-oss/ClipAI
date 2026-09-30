package com.clipai.application.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoUploadValidatorTest {
    private final VideoUploadValidator validator = new VideoUploadValidator(1024);

    @Test
    void allowsSupportedExtensionsAndSanitizesWindowsAndPosixPaths() {
        assertEquals("mp4", validator.validateAndGetExtension("../../capture.MP4", 10));
        assertEquals("recording.mov", validator.safeFilename("C:\\private\\recording.mov"));
    }

    @Test
    void rejectsMissingEmptyUnsupportedAndOversizedFiles() {
        assertThrows(InvalidVideoUploadException.class, () -> validator.validateAndGetExtension(null, 10));
        assertThrows(InvalidVideoUploadException.class, () -> validator.validateAndGetExtension("movie.mp4", 0));
        assertThrows(InvalidVideoUploadException.class, () -> validator.validateAndGetExtension("movie.txt", 10));
        assertThrows(UploadTooLargeException.class, () -> validator.validateAndGetExtension("movie.mp4", 1025));
    }
}
