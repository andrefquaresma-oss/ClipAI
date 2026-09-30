package com.clipai.application.media;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface UploadContent {
    InputStream openStream() throws IOException;
}
