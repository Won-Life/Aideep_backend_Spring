package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.entity.Edge;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.entity.NodeType;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.repository.EdgeRepository;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.global.exception.BusinessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 회의 중 사용자 수정을 막는 잠금이 하위 그래프 전체에 걸리고, 회의가 끝나면 그 회의가 잠근 노드만 풀리는지 실제 컬럼으로 검증한다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Import({NodeLockService.class, NodeGraphQueryService.class})
class NodeLockServiceIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
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
    private NodeLockService nodeLockService;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private EdgeRepository edgeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate meetings, edges, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into users(user_id, email) values (?,?)", USER_ID, "lock@aideep.dev");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "lock");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", OTHER_WORKSPACE_ID, "other");
    }

    @Test
    void locksTheMeetingNodeAndEveryDescendant() {
        UUID root = node("회의 노드", 0);
        UUID child = node("안건", 1);
        UUID grandchild = node("세부 안건", 2);
        UUID unrelated = node("다른 노드", 3);
        edge(root, child);
        edge(child, grandchild);
        UUID meetingId = meeting(root);

        int locked = nodeLockService.lockDescendants(WORKSPACE_ID, root, meetingId, NOW.plusSeconds(10));

        assertThat(locked).isEqualTo(3);
        assertThat(lockedBy(root)).isEqualTo(meetingId);
        assertThat(lockedBy(child)).isEqualTo(meetingId);
        assertThat(lockedBy(grandchild)).isEqualTo(meetingId);
        assertThat(lockedBy(unrelated)).isNull();
    }

    @Test
    void unlocksOnlyTheNodesLockedByThatMeeting() {
        UUID root = node("회의 노드", 0);
        UUID child = node("안건", 1);
        UUID otherRoot = node("다른 회의 노드", 2);
        edge(root, child);
        UUID meetingId = meeting(root);
        UUID otherMeetingId = meeting(otherRoot);
        nodeLockService.lockDescendants(WORKSPACE_ID, root, meetingId, NOW.plusSeconds(10));
        nodeLockService.lockDescendants(WORKSPACE_ID, otherRoot, otherMeetingId, NOW.plusSeconds(10));

        int unlocked = nodeLockService.unlockByMeeting(meetingId, NOW.plusSeconds(20));

        assertThat(unlocked).isEqualTo(2);
        assertThat(lockedBy(root)).isNull();
        assertThat(lockedBy(child)).isNull();
        assertThat(lockedBy(otherRoot)).isEqualTo(otherMeetingId);
    }

    /**
     * 하위 그래프가 겹치면 먼저 잠근 회의가 소유를 유지한다. 나중 회의가 덮어쓰면 먼저 끝난 쪽이 진행 중인 회의의 잠금을 풀어버린다.
     */
    @Test
    void keepsTheFirstOwnerWhenSubtreesOverlap() {
        UUID root = node("회의 노드", 0);
        UUID shared = node("공유 안건", 1);
        UUID secondRoot = node("두 번째 회의 노드", 2);
        edge(root, shared);
        edge(secondRoot, shared);
        UUID firstMeetingId = meeting(root);
        UUID secondMeetingId = meeting(secondRoot);
        nodeLockService.lockDescendants(WORKSPACE_ID, root, firstMeetingId, NOW.plusSeconds(10));

        int locked = nodeLockService.lockDescendants(WORKSPACE_ID, secondRoot, secondMeetingId, NOW.plusSeconds(20));

        assertThat(locked).isEqualTo(1);
        assertThat(lockedBy(shared)).isEqualTo(firstMeetingId);
        assertThat(lockedBy(secondRoot)).isEqualTo(secondMeetingId);

        nodeLockService.unlockByMeeting(secondMeetingId, NOW.plusSeconds(30));

        assertThat(lockedBy(shared)).isEqualTo(firstMeetingId);
        assertThat(lockedBy(secondRoot)).isNull();
    }

    @Test
    void rejectsNodeOfAnotherWorkspace() {
        UUID foreignNode = nodeIn(OTHER_WORKSPACE_ID, "다른 워크스페이스 노드", 0);
        UUID meetingId = meeting(foreignNode, OTHER_WORKSPACE_ID);

        assertThatThrownBy(() -> nodeLockService.lockDescendants(WORKSPACE_ID, foreignNode, meetingId, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(NodeError.NODE_NOT_FOUND));
    }

    @Test
    void unlockIsIdempotentForMeetingsThatLockedNothing() {
        UUID root = node("회의 노드", 0);
        UUID meetingId = meeting(root);

        assertThat(nodeLockService.unlockByMeeting(meetingId, NOW.plusSeconds(10))).isZero();
    }

    private UUID lockedBy(UUID nodeId) {
        // 서비스는 saveAll만 하므로 컬럼으로 확인하려면 영속성 컨텍스트를 먼저 내려보낸다.
        nodeRepository.flush();
        return jdbcTemplate.queryForObject("select locked_by_meeting_id from nodes where node_id = ?",
                UUID.class, nodeId);
    }

    private UUID meeting(UUID nodeId) {
        return meeting(nodeId, WORKSPACE_ID);
    }

    private UUID meeting(UUID nodeId, UUID workspaceId) {
        UUID meetingId = UUID.randomUUID();
        OffsetDateTime createdAt = NOW.atOffset(ZoneOffset.UTC);
        jdbcTemplate.update("""
                insert into meetings(meeting_id, workspace_id, node_id, user_id, bot_id, meeting_url, bot_type,
                                     status, created_at, updated_at)
                values (?,?,?,?,?,?,'GOOGLE'::bot_type_enum,'RECORDING'::meeting_status_enum,?,?)
                """, meetingId, workspaceId, nodeId, USER_ID, UUID.randomUUID(),
                "https://meet.google.com/" + meetingId, createdAt, createdAt);
        return meetingId;
    }

    private UUID node(String title, int order) {
        return nodeIn(WORKSPACE_ID, title, order);
    }

    private UUID nodeIn(UUID workspaceId, String title, int order) {
        Node node = Node.create(workspaceId, title, NodeType.DATA, order, order, "{}", NOW.plusSeconds(order));
        return nodeRepository.saveAndFlush(node).getId();
    }

    private void edge(UUID sourceId, UUID targetId) {
        edgeRepository.saveAndFlush(Edge.create(WORKSPACE_ID, sourceId, targetId, null, null, NOW));
    }
}
