package com.aideep.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.service.JwtTokenService;
import com.aideep.domain.auth.service.RedisAuthStore;
import com.aideep.global.response.ResponseWrappingAdvice;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = ObservabilityIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=prod", "spring.flyway.enabled=false", "management.health.mail.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
        "DD_METRICS_ENABLED=true", "DD_AGENT_HOST=127.0.0.1", "DD_SERVICE=observability-test",
        "DD_ENV=test", "DD_VERSION=integration", "management.statsd.metrics.export.buffered=false"
})
class ObservabilityIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry dynamicPropertyRegistry) {
        dynamicPropertyRegistry.add("DB_URL", POSTGRES::getJdbcUrl);
        dynamicPropertyRegistry.add("DB_USERNAME", POSTGRES::getUsername);
        dynamicPropertyRegistry.add("DB_PASSWORD", POSTGRES::getPassword);
        dynamicPropertyRegistry.add("REDIS_HOST", REDIS::getHost);
        dynamicPropertyRegistry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    JwtTokenService jwtTokenService;
    @MockitoBean
    RedisAuthStore redisAuthStore;
    @MockitoBean
    AuthUserRepository authUserRepository;
    @LocalServerPort
    int port;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void serverProcessesHttpRequests() throws Exception {
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/actuator/health")).GET().build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(httpResponse.statusCode()).isEqualTo(401);
            assertThat(httpResponse.body()).contains("COMMON401");
        }
    }

    @Test
    void actuatorRequiresAuthenticationAndPreservesOriginalResponses() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health").with(user("monitor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.resultType").doesNotExist());
        mockMvc.perform(get("/actuator/metrics").with(user("monitor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.names").isArray())
                .andExpect(jsonPath("$.resultType").doesNotExist());
        for (String endpoint : new String[]{"env", "configprops", "heapdump", "prometheus"}) {
            mockMvc.perform(get("/actuator/" + endpoint).with(user("monitor")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void registersJvmPoolAndHttpMetricsWithCommonTags() throws Exception {
        jdbcTemplate.queryForObject("select 1", Integer.class);
        mockMvc.perform(get("/actuator/health").with(user("monitor"))).andExpect(status().isOk());
        assertThat(meterRegistry.find("jvm.memory.used").gauge()).isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.active").gauge()).isNotNull();
        assertThat(meterRegistry.find("jdbc.connections.max").gauge()).isNotNull();
        assertThat(meterRegistry.find("http.server.requests").timer()).isNotNull();
        assertThat(meterRegistry.find("jvm.memory.used").tags("service", "observability-test",
                "env", "test", "version", "integration").gauge()).isNotNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, ResponseWrappingAdvice.class})
    static class TestConfiguration {
    }
}
