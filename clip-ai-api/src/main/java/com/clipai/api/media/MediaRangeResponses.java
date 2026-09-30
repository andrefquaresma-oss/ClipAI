package com.clipai.api.media;

import org.springframework.core.io.Resource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpRange;
import org.springframework.http.ContentDisposition;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class MediaRangeResponses {
    private MediaRangeResponses() {
    }

    public static ResponseEntity<?> stream(Resource resource, String filename,
                                           MediaType mediaType, HttpHeaders requestHeaders) throws IOException {
        long contentLength = resource.contentLength();
        String rangeHeader = requestHeaders.getFirst(HttpHeaders.RANGE);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString());
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(contentLength);
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }
        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
        HttpRange range = ranges.get(0);
        ResourceRegion region = range.toResourceRegion(resource);
        long first = region.getPosition();
        long last = first + region.getCount() - 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + first + "-" + last + "/" + contentLength);
        headers.setContentLength(region.getCount());
        return new ResponseEntity<>(new ByteRangeResource(resource, first, region.getCount()),
                headers, HttpStatus.PARTIAL_CONTENT);
    }

    private static final class ByteRangeResource extends AbstractResource {
        private final Resource delegate;
        private final long position;
        private final long length;

        private ByteRangeResource(Resource delegate, long position, long length) {
            this.delegate = delegate;
            this.position = position;
            this.length = length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream input = delegate.getInputStream();
            try {
                input.skipNBytes(position);
            } catch (IOException exception) {
                input.close();
                throw exception;
            }
            return new FilterInputStream(input) {
                private long remaining = length;

                @Override
                public int read() throws IOException {
                    if (remaining == 0) {
                        return -1;
                    }
                    int value = super.read();
                    if (value >= 0) {
                        remaining--;
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int byteCount) throws IOException {
                    if (remaining == 0) {
                        return -1;
                    }
                    int read = super.read(bytes, offset, (int) Math.min(byteCount, remaining));
                    if (read > 0) {
                        remaining -= read;
                    }
                    return read;
                }
            };
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public String getDescription() {
            return "Byte range of " + delegate.getDescription();
        }
    }
}
