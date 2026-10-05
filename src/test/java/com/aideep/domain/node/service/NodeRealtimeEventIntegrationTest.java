package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aideep.domain.node.config.NodeEventProperties;
import com.aideep.domain.node.consumer.RedisNodeEventConsumer;
import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.domain.node.repository.ProcessedNodeEventRepository;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NodeCommandService.class, NodeCommandPayloadParser.class, WorkspaceQueryService.class,
        NodeRealtimeEventIntegrationTest.TestBeans.class, NodeEventBusPublisher.class, NodeCommandResultService.class})
class NodeRealtimeEventIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("global/aideep-schema.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @MockitoSpyBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoSpyBean
    private WorkspaceQueryService workspaceQueryService;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

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
    private NodeCommandResultService nodeCommandResultService;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ProcessedNodeEventRepository processedNodeEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NodeEventParser nodeEventParser = new NodeEventParser(objectMapper);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate node_command_results, processed_node_events, nodes, workspaces cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "AI 워크스페이스");
    }

    @Test
    void publishesCommittedSnapshotOnExistingRedisChannelAndInvalidatesCache() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        redisMessageListenerContainer.addMessageListener((message, pattern) ->
                messages.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(NodeEventBusPublisher.CHANNEL));
        redisMessageListenerContainer.afterPropertiesSet();
        redisMessageListenerContainer.start();
        try {
            stringRedisTemplate.opsForValue().set("workspace:sync:" + WORKSPACE_ID, "stale");
            var transactionTemplate = new TransactionTemplate(platformTransactionManager);
            transactionTemplate.executeWithoutResult(status -> {
                nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
                verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
                assertThat(stringRedisTemplate.hasKey("workspace:sync:" + WORKSPACE_ID)).isTrue();
            });
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            var envelope = objectMapper.readTree(message);
            assertThat(envelope.get("v").asInt()).isEqualTo(1);
            assertThat(envelope.get("kind").asString()).isEqualTo("WORKSPACE_EVENT");
            assertThat(envelope.get("origin").asString()).isEqualTo("spring-api");
            assertThat(Instant.parse(envelope.get("publishedAt").asString())).isEqualTo(NOW);
            assertThat(UUID.fromString(envelope.get("messageId").asString())).isNotNull();
            var payload = envelope.get("payload");
            assertThat(payload.get("type").asString()).isEqualTo("NODE_CREATE");
            assertThat(payload.get("userId").asString()).isEqualTo("system:ai");
            assertThat(payload.get("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
            var node = nodeRepository.findAll().getFirst();
            assertThat(payload.get("node").get("nodeId").asString()).isEqualTo(node.getId().toString());
            assertThat(payload.get("node").get("data")).isEqualTo(objectMapper.readTree(node.getContent()));
            assertThat(Instant.parse(payload.get("node").get("createdAt").asString())).isEqualTo(NOW);
            assertThat(stringRedisTemplate.hasKey("workspace:sync:" + WORKSPACE_ID)).isFalse();
        } finally {
            redisMessageListenerContainer.destroy();
        }
    }

    @Test
    void doesNotPublishOrInvalidateCacheOnRollback() {
        stringRedisTemplate.opsForValue().set("workspace:sync:" + WORKSPACE_ID, "unchanged");
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
            nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
            status.setRollbackOnly();
        });
        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
        assertThat(stringRedisTemplate.opsForValue().get("workspace:sync:" + WORKSPACE_ID)).isEqualTo("unchanged");
        assertThat(nodeRepository.count()).isZero();
        assertThat(processedNodeEventRepository.count()).isZero();
    }

    @Test
    void publishesMergedDataAndOmitsUnchangedPatchFields() {
        nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        clearInvocations(stringRedisTemplate);
        nodeCommandService.apply(patchEvent(UUID.fromString("44444444-4444-4444-8444-444444444444"), nodeId, 1,
                "{\"data\": {\"markdownBody\": \"updated\"}}"));
        var argumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq(NodeEventBusPublisher.CHANNEL), argumentCaptor.capture());
        var payload = objectMapper.readTree(argumentCaptor.getValue()).get("payload");
        assertThat(payload.get("type").asString()).isEqualTo("NODE_UPDATE");
        assertThat(payload.get("nodeId").asString()).isEqualTo(nodeId.toString());
        assertThat(payload.get("patch").has("title")).isFalse();
        assertThat(payload.get("patch").has("nodeType")).isFalse();
        assertThat(payload.get("patch").get("data").get("markdownBody").asString()).isEqualTo("updated");
        assertThat(payload.get("patch").get("data").get("color").asString()).isEqualTo("#ffffff");
    }

    @Test
    void keepsDbSuccessWhenCacheInvalidationAndPublicationFail() {
        doThrow(new IllegalStateException("cache unavailable")).when(stringRedisTemplate)
                .delete("workspace:sync:" + WORKSPACE_ID);
        doThrow(new IllegalStateException("pubsub unavailable")).when(stringRedisTemplate)
                .convertAndSend(eq(NodeEventBusPublisher.CHANNEL), anyString());
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var persistentNodeCommandProcessor = new PersistentNodeCommandProcessor(nodeCommandService);
        assertThat(persistentNodeCommandProcessor.process(createEvent(eventId)))
                .isEqualTo(NodeCommandProcessingResult.PROCESSED);
        assertThat(persistentNodeCommandProcessor.process(createEvent(eventId)))
                .isEqualTo(NodeCommandProcessingResult.PROCESSED);
        assertThat(nodeRepository.count()).isEqualTo(1);
        assertThat(processedNodeEventRepository.existsById(eventId)).isTrue();
        verify(stringRedisTemplate).convertAndSend(eq(NodeEventBusPublisher.CHANNEL), anyString());
    }

    @Test
    void duplicateAndRejectedCommandsDoNotPublish() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        nodeCommandService.apply(createEvent(eventId));
        clearInvocations(stringRedisTemplate);
        nodeCommandService.apply(createEvent(eventId));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        assertThatThrownBy(() -> nodeCommandService.apply(patchEvent(
                UUID.fromString("44444444-4444-4444-8444-444444444444"), nodeId, 99, "{\"title\": \"stale\"}")))
                .isInstanceOf(BusinessException.class);
        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
        verify(stringRedisTemplate, never()).delete("workspace:sync:" + WORKSPACE_ID);
    }

    @Test
    void titleOnlyPatchDoesNotSerializeDataOrNodeType() {
        nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        clearInvocations(stringRedisTemplate);
        nodeCommandService.apply(patchEvent(UUID.fromString("44444444-4444-4444-8444-444444444444"), nodeId, 1,
                "{\"title\": \"renamed\"}"));
        var argumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq(NodeEventBusPublisher.CHANNEL), argumentCaptor.capture());
        assertThat(objectMapper.readTree(argumentCaptor.getValue()).get("payload").get("patch"))
                .isEqualTo(objectMapper.readTree("{\"title\": \"renamed\"}"));
    }

    @Test
    void simultaneousDuplicateCommandsPublishOnlyWinningTransaction() throws Exception {
        var cyclicBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            cyclicBarrier.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(workspaceQueryService).existsActiveWorkspace(WORKSPACE_ID);
        var persistentNodeCommandProcessor = new PersistentNodeCommandProcessor(nodeCommandService);
        var event = createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        try (var executorService = Executors.newFixedThreadPool(2)) {
            var first = executorService.submit(() -> persistentNodeCommandProcessor.process(event));
            var second = executorService.submit(() -> persistentNodeCommandProcessor.process(event));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(NodeCommandProcessingResult.PROCESSED);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(NodeCommandProcessingResult.PROCESSED);
        }
        assertThat(nodeRepository.count()).isEqualTo(1);
        assertThat(processedNodeEventRepository.count()).isEqualTo(1);
        verify(stringRedisTemplate).convertAndSend(eq(NodeEventBusPublisher.CHANNEL), anyString());
    }

    @Test
    void streamCommandCommitsPublishesAndAcknowledges() throws Exception {
        processStreamCommand(false);
    }

    @Test
    void streamCommandStillAcknowledgesWhenPublicationFails() throws Exception {
        processStreamCommand(true);
    }

    @Test
    void persistsTerminalFailureBeforeAcknowledging() throws Exception {
        processStreamCommand(false, true);
    }

    private void processStreamCommand(boolean failPublication) throws Exception {
        processStreamCommand(failPublication, false);
    }

    private void processStreamCommand(boolean failPublication, boolean failCommand) throws Exception {
        if (failPublication) {
            doThrow(new IllegalStateException("pubsub unavailable")).when(stringRedisTemplate)
                    .convertAndSend(eq(NodeEventBusPublisher.CHANNEL), anyString());
        }
        String stream = "test:node:commands";
        String group = "test:node:workers";
        stringRedisTemplate.delete(java.util.List.of(stream, "test:node:dlq", "test:node:retries"));
        var nodeEventProperties = new NodeEventProperties(true, stream, "test:node:dlq", "test:node:retries",
                group, "test-worker", 1, Duration.ofMillis(20), Duration.ofSeconds(30),
                Duration.ofSeconds(30), 3, 1000);
        var pollExecutorService = Executors.newSingleThreadExecutor();
        var scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .serializer(StringRedisSerializer.UTF_8).batchSize(1).pollTimeout(Duration.ofMillis(20))
                .executor(pollExecutorService).autoStartup(false).build();
        var streamMessageListenerContainer = StreamMessageListenerContainer.create(
                stringRedisTemplate.getConnectionFactory(), options);
        var nodeEventWorker = new NodeEventWorker(nodeEventParser,
                new PersistentNodeCommandProcessor(nodeCommandService));
        var staticListableBeanFactory = new StaticListableBeanFactory();
        staticListableBeanFactory.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        var redisNodeEventConsumer = new RedisNodeEventConsumer(stringRedisTemplate, nodeEventProperties,
                nodeEventWorker, nodeCommandResultService, streamMessageListenerContainer, scheduledExecutorService,
                staticListableBeanFactory.getBeanProvider(Clock.class));
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        try {
            redisNodeEventConsumer.start();
            stringRedisTemplate.opsForStream().add(stream, Map.of("data", createEventJson(eventId, failCommand ? UUID.randomUUID() : WORKSPACE_ID)));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean acknowledged = false;
            while (System.nanoTime() < deadline) {
                if ((failCommand ? stringRedisTemplate.opsForStream().size("test:node:dlq") > 0
                        : processedNodeEventRepository.existsById(eventId))
                        && stringRedisTemplate.opsForStream().pending(stream, group).getTotalPendingMessages() == 0) {
                    acknowledged = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(acknowledged).isTrue();
            if (failCommand) {
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from node_command_results where command_event_id=?", Long.class, eventId))
                        .isEqualTo(1);
                var result = objectMapper.readTree(jdbcTemplate.queryForObject(
                        "select data from node_command_results where command_event_id=?", String.class, eventId));
                assertThat(result.path("eventType").asString()).isEqualTo("NODE_CREATE_FAILED");
                assertThat(result.at("/payload/error/code").asString()).isEqualTo("NODE-007");
                assertThat(result.at("/payload/error/message").asString()).isNotBlank();
                assertThat(result.at("/payload/error").has("details")).isTrue();
                assertThat(nodeRepository.count()).isZero();
                verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
            } else {
                assertThat(nodeRepository.count()).isEqualTo(1);
                verify(stringRedisTemplate).convertAndSend(eq(NodeEventBusPublisher.CHANNEL), anyString());
                assertThat(stringRedisTemplate.opsForStream().size("test:node:dlq")).isZero();
            }
        } finally {
            redisNodeEventConsumer.stop();
            streamMessageListenerContainer.stop();
            pollExecutorService.shutdownNow();
            scheduledExecutorService.shutdownNow();
        }
    }

    private NodeEventEnvelope createEvent(UUID eventId) {
        return nodeEventParser.parse(createEventJson(eventId, WORKSPACE_ID));
    }

    private String createEventJson(UUID eventId, UUID workspaceId) {
        return """
                {
                  "version": 1,
                  "eventId": "%s",
                  "eventType": "NODE_CREATE_REQUESTED",
                  "occurredAt": "2026-09-21T03:30:00Z",
                  "workspaceId": "%s",
                  "payload": {
                    "title": "AI 회의 요약",
                    "nodeType": "DATA",
                    "position": {"x": 100, "y": 200},
                    "data": {
                      "dataType": "MARKDOWN",
                      "markdownBody": "# 회의 요약",
                      "jsonBody": "{\\"type\\":\\"doc\\",\\"content\\":[]}",
                      "color": "#ffffff",
                      "textColor": "#000000"
                    }
                  }
                }
                """.formatted(eventId, workspaceId);
    }

    private NodeEventEnvelope patchEvent(UUID eventId, UUID nodeId, int expectedVersion, String patch) {
        return patchEvent(eventId, WORKSPACE_ID, nodeId, expectedVersion, patch);
    }

    private NodeEventEnvelope patchEvent(UUID eventId, UUID workspaceId, UUID nodeId, int expectedVersion,
                                         String patch) {
        return nodeEventParser.parse("""
                {
                  "version": 1,
                  "eventId": "%s",
                  "eventType": "NODE_PATCH_REQUESTED",
                  "occurredAt": "2026-09-21T03:35:00Z",
                  "workspaceId": "%s",
                  "payload": {
                    "nodeId": "%s",
                    "expectedVersion": %d,
                    "patch": %s
                  }
                }
                """.formatted(eventId, workspaceId, nodeId, expectedVersion, patch));
    }

    static class TestBeans {
        @org.springframework.context.annotation.Bean(destroyMethod = "destroy")
        LettuceConnectionFactory lettuceConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @org.springframework.context.annotation.Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory lettuceConnectionFactory) {
            return new StringRedisTemplate(lettuceConnectionFactory);
        }

        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
