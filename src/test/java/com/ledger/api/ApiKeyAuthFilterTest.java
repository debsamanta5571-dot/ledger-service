package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthFilterTest {

    @Test
    void databaseOutageIsReportedAs503NotAsABadKey() throws Exception {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        when(keys.findActiveByHash(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(keys, new RateLimiter(), new ObjectMapper(),
                new AuthProperties(null, 60, List.of("accounts:read")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts");
        request.addHeader(ApiKeyAuthFilter.HEADER, "some-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getContentAsString()).contains("urn:ledger:problem:service-unavailable");
        assertThat(chain.getRequest()).as("request must not continue down the chain").isNull();
    }
}
