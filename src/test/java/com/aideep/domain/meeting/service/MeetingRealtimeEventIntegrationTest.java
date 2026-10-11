package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.global.event.RealtimeEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 회의 상태 전이가 커밋된 뒤에만, 그리고 전이당 한 번만 {@code aideep.realtime.v1}로 나가는지 실제 Redis 구독으로 검증한다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MeetingStatusService.class, MeetingRealtimeEventPublisher.class, MeetingQueryService.class,
        MeetingRealtimeEventIntegrationTest.TestBeans.class})
class MeetingRealtimeEventIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BOT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-05T10:10:00Z");

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

    @MockitoSpyBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MeetingStatusService meetingStatusService;

    @Autowired
    private MeetingQueryService meetingQueryService;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate meetings, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "meeting");
        jdbcTemplate.update("insert into users(user_id, email) values (?,?)", USER_ID, "host@example.com");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)", NODE_ID, WORKSPACE_ID,
                "회의 노드");
    }

    @Test
    void publishesBotJoinedOnTheSharedRealtimeChannelOnlyOnce() throws Exception {
        Meeting meeting = savedMeeting();
        var messages = new LinkedBlockingQueue<String>();
        var redisMessageListenerContainer = subscribe(messages);
        try {
            meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
            meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            assertThat(messages.poll(1, TimeUnit.SECONDS)).isNull();

            var envelope = objectMapper.readTree(message);
            assertThat(envelope.get("v").asInt()).isEqualTo(1);
            assertThat(envelope.get("kind").asString()).isEqualTo("WORKSPACE_EVENT");
            assertThat(envelope.get("origin").asString()).isEqualTo("spring-api");
            assertThat(Instant.parse(envelope.get("publishedAt").asString())).isEqualTo(NOW);
            assertThat(UUID.fromString(envelope.get("messageId").asString())).isNotNull();

            var payload = envelope.get("payload");
            assertThat(payload.get("type").asString()).isEqualTo("MEETING_BOT_JOINED");
            assertThat(payload.get("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
            assertThat(payload.get("userId").asString()).isEqualTo(USER_ID.toString());
            assertThat(payload.get("meetingId").asString()).isEqualTo(meeting.getId().toString());
            assertThat(payload.get("nodeId").asString()).isEqualTo(NODE_ID.toString());
            assertThat(payload.get("botId").asString()).isEqualTo(BOT_ID.toString());
            assertThat(payload.get("status").asString()).isEqualTo("RECORDING");
            // 발행 시각(NOW)이 아니라 상태가 발생한 시각이다.
            assertThat(Instant.parse(payload.get("occurredAt").asString())).isEqualTo(CREATED_AT.plusSeconds(10));
            // 라우팅은 WS 서버의 모델이다. 전송 지시는 계약에 없다.
            assertThat(payload.has("targetRoom")).isFalse();
            assertThat(payload.has("socketId")).isFalse();
            assertThat(payload.has("statusSubCode")).isFalse();
        } finally {
            redisMessageListenerContainer.destroy();
        }
    }

    @Test
    void publishesBotFailedWithStatusSubCode() throws Exception {
        savedMeeting();
        var messages = new LinkedBlockingQueue<String>();
        var redisMessageListenerContainer = subscribe(messages);
        try {
            meetingStatusService.apply(BOT_ID, MeetingStatus.FAILED, "meeting_not_found", CREATED_AT.plusSeconds(10));

            var payload = objectMapper.readTree(messages.poll(5, TimeUnit.SECONDS)).get("payload");
            assertThat(payload.get("type").asString()).isEqualTo("MEETING_BOT_FAILED");
            assertThat(payload.get("statusSubCode").asString()).isEqualTo("meeting_not_found");
        } finally {
            redisMessageListenerContainer.destroy();
        }
    }

    @Test
    void doesNotPublishBeforeCommitNorOnRollback() {
        savedMeeting();
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
            meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
            verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
            status.setRollbackOnly();
        });

        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
        assertThat(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.REQUESTED);
    }

    /**
     * best-effort다. 발행이 실패해도 이미 커밋된 상태 전이는 남아야 한다.
     */
    @Test
    void keepsCommittedStatusWhenPublicationFails() {
        savedMeeting();
        doThrow(new IllegalStateException("pubsub unavailable")).when(stringRedisTemplate)
                .convertAndSend(eq(RealtimeEventEnvelope.CHANNEL), anyString());

        assertThatCode(() -> meetingStatusService.apply(
                BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10)))
                .doesNotThrowAnyException();

        assertThat(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.RECORDING);
    }

    /**
     * Pub/Sub은 구독자가 없으면 메시지를 버리므로, 재접속 복구는 이 조회가 담당한다.
     */
    @Test
    void returnsOnlyActiveMeetingsForReconnectRecovery() {
        savedMeeting();
        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        assertThat(meetingQueryService.findActiveMeetings(WORKSPACE_ID))
                .singleElement()
                .satisfies(active -> {
                    assertThat(active.botId()).isEqualTo(BOT_ID);
                    assertThat(active.nodeId()).isEqualTo(NODE_ID);
                    assertThat(active.status()).isEqualTo("RECORDING");
                    assertThat(active.startedAt()).isEqualTo(CREATED_AT.plusSeconds(10));
                });

        meetingStatusService.apply(BOT_ID, MeetingStatus.DONE, null, CREATED_AT.plusSeconds(60));

        assertThat(meetingQueryService.findActiveMeetings(WORKSPACE_ID)).isEmpty();
    }

    private RedisMessageListenerContainer subscribe(LinkedBlockingQueue<String> messages) {
        var redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        redisMessageListenerContainer.addMessageListener((message, pattern) ->
                        messages.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(RealtimeEventEnvelope.CHANNEL));
        redisMessageListenerContainer.afterPropertiesSet();
        redisMessageListenerContainer.start();
        return redisMessageListenerContainer;
    }

    private Meeting savedMeeting() {
        Meeting meeting = Meeting.request(WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.test/abc", Bottype.GOOGLE,
                CREATED_AT);
        meeting.linkBot(BOT_ID, CREATED_AT);
        return meetingRepository.saveAndFlush(meeting);
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
