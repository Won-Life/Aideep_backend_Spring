package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.node.config.NodeEventConfiguration;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false",
        "node.results.publish-interval=50ms"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NodeEventConfiguration.class, NodeCommandService.class, NodeCommandPayloadParser.class,
        WorkspaceQueryService.class, NodeCommandResultPublisherIntegrationTest.TestBeans.class})
class NodeCommandResultPublisherIntegrationTest {
    private static final String STREAM = "onnode:ai:command-results:v1";
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("global/aideep-schema.sql");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry dynamicPropertyRegistry) {
        dynamicPropertyRegistry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        dynamicPropertyRegistry.add("spring.datasource.username", POSTGRES::getUsername);
        dynamicPropertyRegistry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NodeCommandService nodeCommandService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private NodeCommandResultPublisher nodeCommandResultPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate node_command_results, processed_node_events, nodes, workspaces cascade");
        stringRedisTemplate.delete(STREAM);
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "results");
    }

    @Test
    void asynchronouslyPublishesPersistedResultAsDataField() throws Exception {
        UUID eventId = UUID.randomUUID();
        create(eventId);
        await(() -> stringRedisTemplate.opsForStream().size(STREAM) == 1);
        var records = stringRedisTemplate.<String, String>opsForStream().range(STREAM, Range.unbounded());
        assertThat(records).hasSize(1);
        String data = jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId);
        assertThat(records.getFirst().getValue()).containsOnlyKeys("data").containsEntry("data", data);
        assertThat(objectMapper.readTree(data).path("eventType").asString()).isEqualTo("NODE_CREATE_SUCCEEDED");
    }

    @Test
    void duplicateCommandRequestsStableResultReplay() throws Exception {
        UUID eventId = UUID.randomUUID();
        create(eventId);
        await(() -> jdbcTemplate.queryForObject(
                "select count(*) from node_command_results where published_at is not null", Long.class) == 1);
        String original = stringRedisTemplate.<String, String>opsForStream().range(STREAM, Range.unbounded())
                .getFirst().getValue().get("data");
        create(eventId);
        await(() -> stringRedisTemplate.opsForStream().size(STREAM) == 2);
        var records = stringRedisTemplate.<String, String>opsForStream().range(STREAM, Range.unbounded());
        assertThat(records).allSatisfy(record -> assertThat(record.getValue().get("data")).isEqualTo(original));
        assertThat(jdbcTemplate.queryForObject("select count(*) from nodes", Long.class)).isEqualTo(1);
    }

    private void create(UUID eventId) {
        nodeCommandService.apply(new NodeEventParser(objectMapper).parse("""
                {"version":1,"eventId":"%s","eventType":"NODE_CREATE_REQUESTED",
                 "occurredAt":"2026-09-21T03:30:00Z","workspaceId":"%s",
                 "payload":{"title":"result","nodeType":"DATA","position":{"x":0,"y":0},"data":{}}}
                """.formatted(eventId, WORKSPACE_ID)));
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    static class TestBeans {
        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory lettuceConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory lettuceConnectionFactory) {
            return new StringRedisTemplate(lettuceConnectionFactory);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
