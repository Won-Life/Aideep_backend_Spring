package com.aideep.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class MeetingRepositoryIntegrationTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("global/aideep-schema.sql");

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

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate meetings, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "meeting");
        jdbcTemplate.update("insert into users(user_id, email) values (?,?)", USER_ID, "host@example.com");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)", NODE_ID, WORKSPACE_ID,
                "회의 노드");
    }

    private Meeting requested() {
        return Meeting.request(WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.test/abc", Bottype.GOOGLE, NOW);
    }

    @Test
    void persistsMeetingWithEnumColumnsAndNullableTimes() {
        Meeting saved = meetingRepository.saveAndFlush(requested());

        Meeting found = meetingRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(found.getBotType()).isEqualTo(Bottype.GOOGLE);
        assertThat(found.getBotId()).isNull();
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getEndedAt()).isNull();
        assertThat(jdbcTemplate.queryForObject("select status::text from meetings where meeting_id=?", String.class,
                saved.getId())).isEqualTo("REQUESTED");
        assertThat(jdbcTemplate.queryForObject("select bot_type::text from meetings where meeting_id=?", String.class,
                saved.getId())).isEqualTo("GOOGLE");
    }

    @Test
    void findsMeetingByBotIdAfterLinking() {
        UUID botId = UUID.randomUUID();
        Meeting meeting = requested();
        meeting.linkBot(botId, NOW.plusSeconds(1));
        meetingRepository.saveAndFlush(meeting);

        assertThat(meetingRepository.findByBotIdAndDeletedAtIsNull(botId)).isPresent();
        assertThat(meetingRepository.findByBotIdAndDeletedAtIsNull(UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectsDuplicateBotId() {
        UUID botId = UUID.randomUUID();
        Meeting first = requested();
        first.linkBot(botId, NOW);
        meetingRepository.saveAndFlush(first);
        Meeting second = requested();
        second.linkBot(botId, NOW);

        assertThatThrownBy(() -> meetingRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsStatusTransitionWithStartAndEndTimes() {
        Meeting meeting = requested();
        meeting.applyStatus(MeetingStatus.RECORDING, null, NOW.plusSeconds(30), NOW.plusSeconds(31));
        meeting.applyStatus(MeetingStatus.DONE, null, NOW.plusSeconds(600), NOW.plusSeconds(601));
        meetingRepository.saveAndFlush(meeting);

        Meeting found = meetingRepository.findById(meeting.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(found.getStartedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(found.getEndedAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(found.getLastEventAt()).isEqualTo(NOW.plusSeconds(600));
    }

    /**
     * V8의 부분 unique 인덱스가 진행 중인 같은 링크의 두 번째 회의를 막는지 검증한다.
     */
    @Test
    void rejectsSecondActiveMeetingWithTheSameUrl() {
        meetingRepository.saveAndFlush(requested());

        assertThatThrownBy(() -> meetingRepository.saveAndFlush(requested()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_meetings_active_url");
    }

    @Test
    void allowsSameUrlOnceThePreviousMeetingEnded() {
        Meeting ended = requested();
        ended.applyStatus(MeetingStatus.DONE, null, NOW.plusSeconds(10), NOW.plusSeconds(11));
        meetingRepository.saveAndFlush(ended);

        assertThatCode(() -> meetingRepository.saveAndFlush(requested())).doesNotThrowAnyException();
    }

    @Test
    void findsActiveMeetingByUrl() {
        meetingRepository.saveAndFlush(requested());

        assertThat(meetingRepository.existsByMeetingUrlAndStatusInAndDeletedAtIsNull(
                "https://meet.test/abc", MeetingStatus.active())).isTrue();
        assertThat(meetingRepository.existsByMeetingUrlAndStatusInAndDeletedAtIsNull(
                "https://meet.test/other", MeetingStatus.active())).isFalse();
    }

    @Test
    void listsActiveMeetingsOfWorkspace() {
        meetingRepository.saveAndFlush(requested());
        Meeting ended = requested();
        ended.applyStatus(MeetingStatus.DONE, null, NOW.plusSeconds(10), NOW.plusSeconds(11));
        meetingRepository.saveAndFlush(ended);

        List<Meeting> active = meetingRepository.findByWorkspaceIdAndStatusInAndDeletedAtIsNull(WORKSPACE_ID,
                List.of(MeetingStatus.REQUESTED, MeetingStatus.JOINING, MeetingStatus.RECORDING));

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(meetingRepository.findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(WORKSPACE_ID)).hasSize(2);
    }
}
