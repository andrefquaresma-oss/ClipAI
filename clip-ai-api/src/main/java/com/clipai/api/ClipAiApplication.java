package com.clipai.api;

import com.clipai.application.media.MediaAssetApplicationService;
import com.clipai.application.media.MediaAssetRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;

@SpringBootApplication(scanBasePackages = "com.clipai")
@ConfigurationPropertiesScan
@EntityScan(basePackages = "com.clipai.infrastructure.persistence.jpa")
@EnableJpaRepositories(basePackages = "com.clipai.infrastructure.persistence.jpa")
public class ClipAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClipAiApplication.class, args);
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    MediaAssetApplicationService mediaAssetApplicationService(MediaAssetRepository repository, Clock clock) {
        return new MediaAssetApplicationService(repository, clock);
    }
}
