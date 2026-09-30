package com.clipai.infrastructure.transcription;

import com.clipai.application.ports.TranscriptionService.TranscribedSegment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTranscriptionServiceTest {
    @Test
    void sendsAudioPathAndMapsTimestampedSegments() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        byte[] responseBody = """
                {"language":"en","segments":[{"startMs":125,"endMs":975,"text":"Hello"}]}
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcriptions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var response = exchange.getResponseBody()) {
                response.write(responseBody);
            }
        });
        server.start();

        try {
            var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(5));
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(requestFactory)
                    .build();
            var service = new HttpTranscriptionService(restClient);
            Path audioPath = Path.of("audio with spaces.wav").toAbsolutePath();

            var result = service.transcribe(audioPath, "ENGLISH");

            var json = new ObjectMapper().readTree(requestBody.get());
            assertEquals(audioPath.toString(), json.path("audioPath").asText());
            assertEquals("en", json.path("language").asText());
            assertEquals(MediaType.APPLICATION_JSON_VALUE, contentType.get().split(";")[0]);
            assertNull(upgradeHeader.get());
            assertEquals("en", result.language());
            assertEquals(new TranscribedSegment(125, 975, "Hello"), result.segments().getFirst());

            service.transcribe(audioPath, null);
            json = new ObjectMapper().readTree(requestBody.get());
            assertTrue(json.has("language"));
            assertTrue(json.path("language").isNull());
        } finally {
            server.stop(0);
        }
    }
}
