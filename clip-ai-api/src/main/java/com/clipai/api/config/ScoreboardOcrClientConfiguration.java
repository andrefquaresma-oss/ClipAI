package com.clipai.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class ScoreboardOcrClientConfiguration {
    @Bean
    RestClient scoreboardOcrRestClient(RestClient.Builder builder, ScoreboardOcrProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return builder.baseUrl(properties.url().replaceAll("/+$", ""))
                .requestFactory(requestFactory).build();
    }
}
