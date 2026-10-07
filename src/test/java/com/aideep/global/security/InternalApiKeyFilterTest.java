package com.aideep.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.global.config.InternalApiProperties;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InternalApiKeyFilterTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private InternalApiKeyFilter internalApiKeyFilter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        internalApiKeyFilter = new InternalApiKeyFilter(new InternalApiProperties(KEY), jsonMapper);
        request = new MockHttpServletRequest("GET", "/v1/aideep/api/internal/workspaces/w/nodes/n");
        response = new MockHttpServletResponse();
    }

    @Test
    void passesRequestWithMatchingKey() throws Exception {
        request.addHeader(InternalApiKeyFilter.KEY_HEADER, KEY);
        MockFilterChain filterChain = new MockFilterChain();

        internalApiKeyFilter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingKeyWithCommonUnauthorizedBody() throws Exception {
        FilterChain filterChain = (servletRequest, servletResponse) -> {
            throw new AssertionError("필터를 통과해서는 안 된다.");
        };

        internalApiKeyFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        JsonNode body = jsonMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("resultType").asString()).isEqualTo("FAIL");
        assertThat(body.path("error").path("errorCode").asString()).isEqualTo("COMMON401");
        assertThat(body.path("success").isNull()).isTrue();
    }

    @Test
    void rejectsWrongKey() throws Exception {
        request.addHeader(InternalApiKeyFilter.KEY_HEADER, KEY.replace('0', '1'));
        FilterChain filterChain = (servletRequest, servletResponse) -> {
            throw new AssertionError("필터를 통과해서는 안 된다.");
        };

        internalApiKeyFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsKeyThatOnlySharesAPrefix() throws Exception {
        request.addHeader(InternalApiKeyFilter.KEY_HEADER, KEY.substring(0, 16));
        FilterChain filterChain = (servletRequest, servletResponse) -> {
            throw new AssertionError("필터를 통과해서는 안 된다.");
        };

        internalApiKeyFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * 인증 없이 열려 있는 내부 API를 만들지 않기 위한 fail-fast다.
     */
    @Test
    void failsFastWhenKeyIsMissingOrTooShort() {
        assertThatThrownBy(() -> new InternalApiProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
        assertThatThrownBy(() -> new InternalApiProperties(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
        assertThatThrownBy(() -> new InternalApiProperties("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
