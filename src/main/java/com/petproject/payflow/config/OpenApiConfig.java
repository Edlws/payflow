package com.petproject.payflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payflowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PayFlow API")
                .version("v1")
                .description("""
                        Mini payment system: account management and money transfers with
                        idempotency, row-level locking, Redis caching and Kafka events.
                        """)
                .contact(new Contact().name("PayFlow"))
                .license(new License().name("MIT")));
    }
}
