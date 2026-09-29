package com.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class OpenApiIT extends AbstractIntegrationTest {

    @Test
    void apiDocsDescribeEveryEndpointAndTheApiKeyScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Ledger Service API"))
                .andExpect(jsonPath("$.paths['/accounts']").exists())
                .andExpect(jsonPath("$.paths['/accounts/{id}']").exists())
                .andExpect(jsonPath("$.paths['/accounts/{id}/statements']").exists())
                .andExpect(jsonPath("$.paths['/transfers']").exists())
                .andExpect(jsonPath("$.paths['/transfers'].post.parameters[?(@.name == 'Idempotency-Key')]").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"));
    }

    @Test
    void swaggerUiIsServedAtSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("swagger-ui")));
    }
}
