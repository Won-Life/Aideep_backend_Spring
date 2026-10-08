package com.aideep.domain.meeting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aideep.domain.meeting.config.MeetingEventProperties;
import com.aideep.domain.meeting.config.RecallProperties;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.domain.meeting.security.RecallWebhookVerifier;
import com.aideep.domain.meeting.service.MeetingEventPublisher;
import com.aideep.domain.meeting.service.MeetingStatusService;
import com.aideep.domain.meeting.service.RecallStatusMapper;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 PostgreSQL에 저장된 회의를 대상으로, 서명된 Recall 웹훅 요청이 봇 상태를 반영하고 그 결과를 로그로 남기는지 확인한다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MeetingStatusService.class, RecallStatusMapper.class, MeetingEventPublisher.class,
        RecallWebhookIntegrationTest.TestBeans.class})
class RecallWebhookIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BOT_ID = UUID.fromString("07190000-0000-4000-8000-000000000000");
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T01:02:03Z");
    private static final Instant NOW = Instant.parse("2026-10-06T01:02:05Z");
    private static final String WEBHOOK_ID = "msg_2abcDEF";
    private static final String SECRET_KEY = Base64.getEncoder()
            .encodeToString("recall-webhook-key".getBytes(StandardCharsets.UTF_8));
    private static final String PATH = "/v1/aideep/api/webhooks/recall/bot-status";

    private static final String MEETING_EVENT_STREAM = "onnode:ai:meeting-events:v1";

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
    private MeetingRepository meetingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeetingStatusService meetingStatusService;

    @Autowired
    private RecallStatusMapper recallStatusMapper;

    @Autowired
    private RecallWebhookVerifier recallWebhookVerifier;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

    private Logger controllerLogger;
    private MockMvc mockMvc;
    private UUID meetingId;

    @BeforeEach
    void setUp() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        jdbcTemplate.execute("truncate meetings, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "meeting");
        jdbcTemplate.update("insert into users(user_id, email) values (?,?)", USER_ID, "host@example.com");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)", NODE_ID, WORKSPACE_ID,
                "회의 노드");
        Meeting meeting = Meeting.request(WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.google.com/abc-defg-hij",
                Bottype.GOOGLE, CREATED_AT.minusSeconds(60));
        meeting.linkBot(BOT_ID, CREATED_AT.minusSeconds(59));
        meetingId = meetingRepository.saveAndFlush(meeting).getId();

        mockMvc = MockMvcBuilders.standaloneSetup(new RecallWebhookController(
                        recallWebhookVerifier, recallStatusMapper, meetingStatusService,
                        JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();

        listAppender.start();
        controllerLogger = (Logger) LoggerFactory.getLogger(RecallWebhookController.class);
        controllerLogger.setLevel(Level.INFO);
        controllerLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void appliesRecordingStatusAndLogsIt() throws Exception {
        perform(body("in_call_recording", null)).andExpect(status().isOk());

        Meeting found = meetingRepository.findById(meetingId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        assertThat(found.getStartedAt()).isEqualTo(CREATED_AT);
        assertThat(found.getLastEventAt()).isEqualTo(CREATED_AT);
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("Recall 봇 상태를 반영했습니다.")
                .contains("botId=" + BOT_ID)
                .contains("code=in_call_recording")
                .contains("result=RECORDING_STARTED"));
        messages().forEach(System.out::println);
    }

    @Test
    void logsRedeliveryAsAppliedWithoutStartingRecordingTwice() throws Exception {
        perform(body("in_call_recording", null)).andExpect(status().isOk());
        perform(body("in_call_recording", null)).andExpect(status().isOk());

        assertThat(meetingRepository.findById(meetingId).orElseThrow().getStartedAt()).isEqualTo(CREATED_AT);
        assertThat(messages().stream().filter(message -> message.contains("result=RECORDING_STARTED")).count())
                .isEqualTo(1);
        assertThat(messages()).anySatisfy(message -> assertThat(message).contains("result=APPLIED"));
        messages().forEach(System.out::println);
    }

    @Test
    void logsUnknownBotWithoutChangingAnyMeeting() throws Exception {
        String unknownBot = body("in_call_recording", null)
                .replace(BOT_ID.toString(), "09990000-0000-4000-8000-000000000000");

        perform(unknownBot).andExpect(status().isOk());

        assertThat(meetingRepository.findById(meetingId).orElseThrow().getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(messages()).anySatisfy(message -> assertThat(message).contains("result=UNKNOWN_BOT"));
        messages().forEach(System.out::println);
    }

    /**
     * 실제 워크스페이스는 상태별 이벤트 이름과 {@code data.bot.id} / {@code data.data.*}를 쓰는 legacy 변형으로 보낸다.
     */
    @Test
    void appliesRecordingStatusFromLegacyPerStatusEvent() throws Exception {
        String legacyBody = """
                {
                  "event": "bot.in_call_recording",
                  "data": {
                    "bot": { "id": "%s" },
                    "data": {
                      "code": "in_call_recording",
                      "sub_code": null,
                      "updated_at": "%s"
                    }
                  }
                }
                """.formatted(BOT_ID, CREATED_AT);

        perform(legacyBody).andExpect(status().isOk());

        Meeting found = meetingRepository.findById(meetingId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        assertThat(found.getStartedAt()).isEqualTo(CREATED_AT);
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("Recall 봇 상태를 반영했습니다.")
                .contains("code=in_call_recording")
                .contains("result=RECORDING_STARTED"));
        messages().forEach(System.out::println);
    }

    @Test
    void logsRawBodyWhenNeitherVariantCanBeRead() throws Exception {
        String unreadable = """
                {"event": "bot.in_call_recording", "data": {"unexpected": true}}
                """;

        perform(unreadable).andExpect(status().isOk());

        assertThat(meetingRepository.findById(meetingId).orElseThrow().getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("Recall 웹훅에서 봇 상태를 읽을 수 없습니다.")
                .contains("unexpected"));
        messages().forEach(System.out::println);
    }

    @Test
    void logsStatusCodeTheDomainDoesNotTrackWithoutChangingTheMeeting() throws Exception {
        perform(body("analysis_done", null)).andExpect(status().isOk());

        assertThat(meetingRepository.findById(meetingId).orElseThrow().getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("매핑하지 않는 Recall 상태 코드입니다.")
                .contains("code=analysis_done"));
        messages().forEach(System.out::println);
    }

    @Test
    void rejectsRequestWhoseSignatureDoesNotCoverTheSentBody() throws Exception {
        String signed = body("in_call_recording", null);
        String tampered = body("call_ended", "timeout_exceeded_everyone_left");

        mockMvc.perform(post(PATH)
                        .header(RecallWebhookVerifier.ID_HEADER, WEBHOOK_ID)
                        .header(RecallWebhookVerifier.TIMESTAMP_HEADER, timestamp())
                        .header(RecallWebhookVerifier.SIGNATURE_HEADER, signature(signed))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        assertThat(meetingRepository.findById(meetingId).orElseThrow().getStatus()).isEqualTo(MeetingStatus.REQUESTED);
    }

    @Test
    void publishesMeetingStartedEventAfterCommitOnlyOnce() throws Exception {
        perform(body("in_call_recording", null)).andExpect(status().isOk());
        perform(body("in_call_recording", null)).andExpect(status().isOk());
        perform(body("call_ended", "timeout_exceeded_everyone_left")).andExpect(status().isOk());

        List<MapRecord<String, Object, Object>> entries = readMeetingEvents();
        assertThat(entries).hasSize(1);
        JsonNode published = new ObjectMapper().readTree(String.valueOf(entries.getFirst().getValue().get("data")));
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(published.path("type").asString()).isEqualTo("MEETING_STARTED");
        assertThat(published.path("source").asString()).isEqualTo("spring-api");
        assertThat(published.path("eventId").asString()).isNotBlank();
        assertThat(published.path("occurredAt").asString()).isEqualTo(CREATED_AT.toString());
        assertThat(published.path("payload").path("meetingId").asString()).isEqualTo(meetingId.toString());
        assertThat(published.path("payload").path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        assertThat(published.path("payload").path("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(published.path("payload").path("botId").asString()).isEqualTo(BOT_ID.toString());
        System.out.println(entries.getFirst().getValue().get("data"));
    }

    @Test
    void doesNotPublishWhenRecordingNeverStarted() throws Exception {
        perform(body("joining_call", null)).andExpect(status().isOk());
        perform(body("fatal", "meeting_not_found")).andExpect(status().isOk());

        assertThat(readMeetingEvents()).isEmpty();
    }

    @Test
    void doesNotPublishForUnknownBot() throws Exception {
        String unknownBot = body("in_call_recording", null)
                .replace(BOT_ID.toString(), "09990000-0000-4000-8000-000000000000");

        perform(unknownBot).andExpect(status().isOk());

        assertThat(readMeetingEvents()).isEmpty();
    }

    private List<MapRecord<String, Object, Object>> readMeetingEvents() {
        List<MapRecord<String, Object, Object>> entries = stringRedisTemplate.opsForStream()
                .range(MEETING_EVENT_STREAM, org.springframework.data.domain.Range.unbounded());
        return entries == null ? List.of() : entries;
    }

    private List<String> messages() {
        return listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private org.springframework.test.web.servlet.ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header(RecallWebhookVerifier.ID_HEADER, WEBHOOK_ID)
                .header(RecallWebhookVerifier.TIMESTAMP_HEADER, timestamp())
                .header(RecallWebhookVerifier.SIGNATURE_HEADER, signature(body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String code, String subCode) {
        String serializedSubCode = subCode == null ? "null" : "\"" + subCode + "\"";
        return """
                {
                  "event": "bot.status_change",
                  "data": {
                    "bot_id": "%s",
                    "status": {
                      "code": "%s",
                      "sub_code": %s,
                      "message": null,
                      "created_at": "%s"
                    }
                  }
                }
                """.formatted(BOT_ID, code, serializedSubCode, CREATED_AT);
    }

    private String timestamp() {
        return String.valueOf(NOW.getEpochSecond());
    }

    private String signature(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET_KEY), "HmacSHA256"));
            mac.update((WEBHOOK_ID + "." + timestamp() + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 웹훅 검증과 상태 반영이 운영과 같은 빈 구성(주입된 Clock, 서비스의 트랜잭션 경계)에서 동작하는지 확인하기 위한 구성이다.
     */
    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        RecallProperties recallProperties() {
            return new RecallProperties(
                    "https://example.test/api/v1/bot/", "recall-key", "AIDEEP Notetaker",
                    "whsec_" + SECRET_KEY, "");
        }

        @Bean
        RecallWebhookVerifier recallWebhookVerifier(RecallProperties recallProperties, Clock clock) {
            return new RecallWebhookVerifier(recallProperties, clock);
        }

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory lettuceConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory lettuceConnectionFactory) {
            return new StringRedisTemplate(lettuceConnectionFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeetingEventProperties meetingEventProperties() {
            return new MeetingEventProperties(true, MEETING_EVENT_STREAM);
        }
    }
}
