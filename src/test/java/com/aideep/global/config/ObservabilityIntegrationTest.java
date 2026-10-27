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
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
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
        "internal.api.key=internal-test-key-at-least-32-bytes",
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
        for (String endpoint : new String[]{"env", "configprops", "heapdump"}) {
            mockMvc.perform(get("/actuator/" + endpoint).with(user("monitor")))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * Prometheus 스크레이퍼는 JWT를 들고 올 수 없어 이 경로만 인증 없이 연다. 외부 노출은 네트워크에서 막는 전제이므로, 다른 actuator
     * 엔드포인트가 같이 열리지 않았는지 함께 확인한다.
     */
    @Test
    void exposesPrometheusScrapeEndpointWithoutAuthentication() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("service=\"observability-test\"");
        assertThat(body).contains("env=\"test\"");
        mockMvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void registersJvmPoolAndHttpMetricsWithCommonTags() throws Exception {
        jdbcTemplate.queryForObject("select 1", Integer.class);
        mockMvc.perform(get("/actuator/health").with(user("monitor"))).andExpect(status().isOk());
        assertThat(meterRegistry.find("jvm.memory.used").gauge()).isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.active").gauge()).isNotNull();
        assertThat(meterRegistry.find("jdbc.connections.max").gauge()).isNotNull();
        assertThat(meterRegistry.find("http.server.requests").timer()).isNotNull();
        // 레지스트리가 statsd와 prometheus 둘이라 주입되는 MeterRegistry는 Composite이다. 공통 태그는 각 자식 레지스트리에 걸린
        // MeterFilter로 붙으므로 Composite에서는 조회되지 않는다. 실제로 내보내는 쪽에 붙었는지를 확인한다.
        Collection<MeterRegistry> registries = meterRegistry instanceof CompositeMeterRegistry composite
                ? composite.getRegistries() : List.of(meterRegistry);
        assertThat(registries).isNotEmpty().allSatisfy(registry ->
                assertThat(registry.find("jvm.memory.used").tags("service", "observability-test",
                        "env", "test", "version", "integration").gauge()).isNotNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, ResponseWrappingAdvice.class})
    static class TestConfiguration {
    }
}
