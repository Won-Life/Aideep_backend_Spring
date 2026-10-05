package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aideep.domain.node.config.NodeResultProperties;
import com.aideep.domain.node.entity.NodeCommandResult;
import com.aideep.domain.node.repository.NodeCommandResultRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class NodeCommandResultPublisherTest {
    private static final String STREAM = "onnode:ai:command-results:v1";
    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");
    private final NodeCommandResultRepository nodeCommandResultRepository = mock(NodeCommandResultRepository.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
    private final PlatformTransactionManager platformTransactionManager = mock(PlatformTransactionManager.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(NodeCommandResultPublisher.class);
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    private final NodeCommandResult nodeCommandResult = new NodeCommandResult(
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "{\"privatePayload\":\"not-for-logs\"}", NOW);
    private NodeCommandResultPublisher nodeCommandResultPublisher;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        listAppender.start();
        logger.addAppender(listAppender);
        given(stringRedisTemplate.opsForStream()).willReturn(streamOperations);
        given(platformTransactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(nodeCommandResultRepository.lockNextUnpublished()).willReturn(Optional.of(nodeCommandResult));
        nodeCommandResultPublisher = new NodeCommandResultPublisher(nodeCommandResultRepository, stringRedisTemplate,
                new NodeResultProperties(true, STREAM, Duration.ofSeconds(1), 1),
                mock(ScheduledExecutorService.class), platformTransactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        listAppender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void logsRedisPublicationWithCorrelationIdsWithoutPayload() {
        given(streamOperations.add(STREAM, Map.of("data", nodeCommandResult.getData())))
                .willReturn(RecordId.of("123456789-0"));

        nodeCommandResultPublisher.publishPending();

        assertThat(listAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("Node command result published",
                    "stream=" + STREAM, "entryId=123456789-0", "eventId=" + nodeCommandResult.getId(),
                    "commandEventId=" + nodeCommandResult.getCommandEventId());
        });
        assertThat(listAppender.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("not-for-logs"));
    }

    @Test
    void doesNotLogPublicationWhenRedisRejectsWrite() {
        given(streamOperations.add(STREAM, Map.of("data", nodeCommandResult.getData())))
                .willThrow(new IllegalStateException("Redis unavailable"));

        nodeCommandResultPublisher.publishPending();

        assertThat(listAppender.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("Node command result published"));
        assertThat(listAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("retained for retry");
        });
        assertThat(nodeCommandResult.getPublishedAt()).isNull();
    }

    @Test
    void doesNotLogPublicationWhenRedisReturnsNoEntryId() {
        nodeCommandResultPublisher.publishPending();

        assertThat(listAppender.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("Node command result published"));
        assertThat(nodeCommandResult.getPublishedAt()).isNull();
    }
}
