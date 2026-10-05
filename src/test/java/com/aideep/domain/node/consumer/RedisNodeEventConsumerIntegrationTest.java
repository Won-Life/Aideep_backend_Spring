package com.aideep.domain.node.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.node.config.NodeEventProperties;
import com.aideep.domain.node.service.NodeCommandProcessingResult;
import com.aideep.domain.node.service.NodeCommandResultService;
import com.aideep.domain.node.service.NodeCommandProcessor;
import com.aideep.domain.node.service.NodeEventParser;
import com.aideep.domain.node.service.NodeEventWorker;
import com.aideep.domain.node.support.DeferredNodeCommandProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class RedisNodeEventConsumerIntegrationTest {
    private static final String STREAM = "onnode:ai:commands:v1";
    private static final String DLQ = "onnode:ai:commands:dlq:v1";
    private static final String RETRIES = "onnode:ai:commands:retries:v1";
    private static final String GROUP = "aideep-node-command-workers-v1";
    private static final String VALID_EVENT = """
            {
              "version": 1,
              "eventId": "11111111-1111-4111-8111-111111111111",
              "eventType": "NODE_CREATE_REQUESTED",
              "occurredAt": "2026-09-21T03:30:00Z",
              "workspaceId": "22222222-2222-4222-8222-222222222222",
              "payload": {"title": "AI meeting summary"}
            }
            """;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory lettuceConnectionFactory;
    private StringRedisTemplate stringRedisTemplate;
    private ExecutorService pollExecutorService;
    private ScheduledExecutorService maintenanceExecutorService;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer;
    private RedisNodeEventConsumer redisNodeEventConsumer;
    private NodeCommandResultService nodeCommandResultService;

    @BeforeEach
    void setUp() {
        nodeCommandResultService = org.mockito.Mockito.mock(NodeCommandResultService.class);
        org.mockito.Mockito.when(nodeCommandResultService.recordFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        lettuceConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        lettuceConnectionFactory.afterPropertiesSet();
        lettuceConnectionFactory.start();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        stringRedisTemplate.afterPropertiesSet();
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        if (redisNodeEventConsumer != null) {
            redisNodeEventConsumer.stop();
        }
        if (pollExecutorService != null) {
            pollExecutorService.shutdownNow();
        }
        if (maintenanceExecutorService != null) {
            maintenanceExecutorService.shutdownNow();
        }
        if (lettuceConnectionFactory != null) {
            lettuceConnectionFactory.destroy();
        }
    }

    @Test
    void keepsValidEventPendingWithoutIncreasingRetryAttemptsUntilProcessorIsReady() throws Exception {
        addEvent(VALID_EVENT);
        startConsumer(new DeferredNodeCommandProcessor(), 5);

        await(() -> pendingCount() == 1);
        Thread.sleep(150);

        assertThat(pendingCount()).isEqualTo(1);
        assertThat(stringRedisTemplate.opsForHash().size(RETRIES)).isZero();
        assertThat(stringRedisTemplate.opsForStream()
                .pending(STREAM, GROUP, Range.unbounded(), 1).get(0).getTotalDeliveryCount()).isEqualTo(1);
    }

    @Test
    void readsPreexistingEventFromStartAndAcknowledgesSuccessfulProcessing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        addEvent(VALID_EVENT);
        startConsumer(nodeEventEnvelope -> {
            calls.incrementAndGet();
            return NodeCommandProcessingResult.PROCESSED;
        }, 5);

        await(() -> groupExists() && pendingCount() == 0 && calls.get() == 1);

        assertThat(calls).hasValue(1);
        assertThat(stringRedisTemplate.opsForHash().size(RETRIES)).isZero();
    }

    @Test
    void movesMalformedEventToDlqBeforeAcknowledgingOriginal() throws Exception {
        addEvent("{");
        startConsumer(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED, 5);

        await(() -> streamSize(DLQ) == 1 && pendingCount() == 0);

        var dlqRecords = stringRedisTemplate.<String, String>opsForStream().range(DLQ, Range.unbounded());
        assertThat(dlqRecords).hasSize(1);
        assertThat(dlqRecords.getFirst().getValue())
                .containsEntry("sourceStream", STREAM)
                .containsEntry("data", "{")
                .containsEntry("errorCode", "NODE-001")
                .containsEntry("attempts", "1");
    }

    @Test
    void movesEntryWithoutDataToDlqWithNumericNodeCode() throws Exception {
        stringRedisTemplate.<String, String>opsForStream().add(STREAM, Map.of("unexpected", "value"));
        startConsumer(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED, 5);

        await(() -> streamSize(DLQ) == 1 && pendingCount() == 0);

        var dlqRecord = stringRedisTemplate.<String, String>opsForStream()
                .range(DLQ, Range.unbounded()).getFirst();
        assertThat(dlqRecord.getValue()).containsEntry("errorCode", "NODE-010")
                .containsEntry("failureCategory", "PERMANENT_FAILURE");
    }

    @Test
    void reclaimsRetryableFailureAndMovesItToDlqAtConfiguredLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        addEvent(VALID_EVENT);
        startConsumer(nodeEventEnvelope -> {
            calls.incrementAndGet();
            throw new IllegalStateException("temporary failure");
        }, 2);

        await(() -> streamSize(DLQ) == 1 && pendingCount() == 0);

        assertThat(calls).hasValue(2);
        assertThat(stringRedisTemplate.opsForHash().size(RETRIES)).isZero();
        var dlqRecord = stringRedisTemplate.<String, String>opsForStream()
                .range(DLQ, Range.unbounded()).getFirst();
        assertThat(dlqRecord.getValue()).containsEntry("attempts", "2")
                .containsEntry("failureCategory", "RETRYABLE_FAILURE");
    }

    @Test
    void doesNotAcknowledgeOriginalWhenDlqWriteFails() throws Exception {
        stringRedisTemplate.opsForValue().set(DLQ, "wrong-redis-type");
        addEvent("{");
        startConsumer(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED, 5);

        await(() -> pendingCount() == 1);
        Thread.sleep(150);

        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void doesNotDlqACommandWhoseSuccessWonTheTerminalRace() throws Exception {
        org.mockito.Mockito.when(nodeCommandResultService.recordFailure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
        addEvent(VALID_EVENT);
        startConsumer(nodeEventEnvelope -> {
            throw new IllegalStateException("another transaction already succeeded");
        }, 1);
        await(() -> groupExists() && pendingCount() == 0);
        assertThat(streamSize(DLQ)).isZero();
    }

    @Test
    void processesOnlyOneEventAtATimeWithinAnInstance() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        addEvent(VALID_EVENT);
        addEvent(VALID_EVENT.replace("11111111-1111-4111-8111-111111111111",
                "33333333-3333-4333-8333-333333333333"));
        startConsumer(nodeEventEnvelope -> {
            int activeCount = active.incrementAndGet();
            maxActive.accumulateAndGet(activeCount, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("processor interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
            completed.incrementAndGet();
            return NodeCommandProcessingResult.PROCESSED;
        }, 5);

        await(() -> completed.get() == 2 && pendingCount() == 0);

        assertThat(maxActive).hasValue(1);
    }

    private void startConsumer(NodeCommandProcessor nodeCommandProcessor, long maxAttempts) {
        NodeEventProperties nodeEventProperties = properties(maxAttempts);
        pollExecutorService = Executors.newSingleThreadExecutor();
        maintenanceExecutorService = Executors.newSingleThreadScheduledExecutor();
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .serializer(StringRedisSerializer.UTF_8)
                .batchSize(1)
                .pollTimeout(Duration.ofMillis(20))
                .executor(pollExecutorService)
                .autoStartup(false)
                .build();
        listenerContainer = StreamMessageListenerContainer.create(lettuceConnectionFactory, options);
        NodeEventWorker nodeEventWorker = new NodeEventWorker(new NodeEventParser(new ObjectMapper()),
                nodeCommandProcessor);
        var beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("clock", Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
        redisNodeEventConsumer = new RedisNodeEventConsumer(stringRedisTemplate, nodeEventProperties, nodeEventWorker,
                nodeCommandResultService,
                listenerContainer, maintenanceExecutorService, beanFactory.getBeanProvider(Clock.class));
        redisNodeEventConsumer.start();
    }

    private NodeEventProperties properties(long maxAttempts) {
        return new NodeEventProperties(true, STREAM, DLQ, RETRIES, GROUP, "test-consumer", 1,
                Duration.ofMillis(20), Duration.ofMillis(50), Duration.ofMillis(50), maxAttempts, 100_000);
    }

    private void addEvent(String data) {
        stringRedisTemplate.<String, String>opsForStream().add(STREAM, Map.of("data", data));
    }

    private boolean groupExists() {
        try {
            return stringRedisTemplate.opsForStream().groups(STREAM).stream()
                    .anyMatch(group -> GROUP.equals(group.groupName()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private long pendingCount() {
        try {
            return stringRedisTemplate.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private long streamSize(String stream) {
        Long size = stringRedisTemplate.opsForStream().size(stream);
        return size == null ? 0 : size;
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
}
