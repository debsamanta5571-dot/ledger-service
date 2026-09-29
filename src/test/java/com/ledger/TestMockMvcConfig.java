package com.ledger;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Makes the autowired MockMvc authenticate every request with the bootstrap API key. */
@TestConfiguration
public class TestMockMvcConfig {

    @Bean
    MockMvcBuilderCustomizer apiKeyHeader() {
        return builder -> builder.defaultRequest(
                MockMvcRequestBuilders.get("/").header("X-API-Key", AbstractIntegrationTest.API_KEY));
    }
}
