package com.clipai.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI clipAiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Clip AI API")
                .version("0.1.0")
                .description("VOD metadata registration and lookup API."));
    }
}
