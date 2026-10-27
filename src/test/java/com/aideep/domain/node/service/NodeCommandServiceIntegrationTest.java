package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.domain.node.repository.ProcessedNodeEventRepository;
import com.aideep.domain.workspace.repository.WorkspaceRepository;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NodeCommandResultService.class, NodeCommandService.class, NodeCommandPayloadParser.class, WorkspaceQueryService.class,
        NodeCommandServiceIntegrationTest.TestBeans.class})
class NodeCommandServiceIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager platformTransactionManager;

    @Autowired
    private NodeCommandService nodeCommandService;

    @Autowired
    private NodeCommandResultService nodeCommandResultService;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ProcessedNodeEventRepository processedNodeEventRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NodeEventParser nodeEventParser = new NodeEventParser(objectMapper);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate node_command_results, processed_node_events, nodes, workspaces cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "AI 워크스페이스");
    }

    @Test
    void createsNodeAndRecordsProcessedEventInSameTransaction() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        nodeCommandService.apply(createEvent(eventId));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select title, node_type::text as node_type, position_x, position_y, version, depth,"
                        + " content->>'markdownBody' as markdown from nodes where workspace_id=?", WORKSPACE_ID);
        assertThat(row).containsEntry("title", "AI 회의 요약")
                .containsEntry("node_type", "DATA")
                .containsEntry("position_x", 100.0)
                .containsEntry("position_y", 200.0)
                .containsEntry("version", 1)
                .containsEntry("depth", 0)
                .containsEntry("markdown", "# 회의 요약");
        assertThat(processedNodeEventRepository.existsById(eventId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select event_type from processed_node_events where event_id=?", String.class, eventId))
                .isEqualTo("NODE_CREATE_REQUESTED");
    }

    @Test
    void createsEdgeFromParentInSameTransactionWhenParentNodeIdIsGiven() {
        nodeCommandService.apply(createEvent(UUID.randomUUID()));
        UUID parentId = nodeRepository.findAll().getFirst().getId();
        UUID childEventId = UUID.randomUUID();

        nodeCommandService.apply(nodeEventParser.parse(
                createEventJson(childEventId, WORKSPACE_ID).replace("\"payload\": {",
                        "\"payload\": {\"parentNodeId\": \"" + parentId + "\", \"sourceHandle\": \"right\","
                                + " \"targetHandle\": \"left\",")));

        UUID childId = UUID.fromString(jdbcTemplate.queryForObject(
                "select node_id::text from nodes where node_id <> ?", String.class, parentId));
        Map<String, Object> edge = jdbcTemplate.queryForMap(
                "select source_id, target_id, source_handle, target_handle, workspace_id from edges");
        assertThat(edge).containsEntry("source_id", parentId)
                .containsEntry("target_id", childId)
                .containsEntry("source_handle", "right")
                .containsEntry("target_handle", "left")
                .containsEntry("workspace_id", WORKSPACE_ID);
    }

    @Test
    void failsCreateAndRollsBackWhenParentNodeDoesNotExistInWorkspace() {
        UUID eventId = UUID.randomUUID();
        UUID missingParent = UUID.fromString("77777777-7777-4777-8777-777777777777");

        assertThatThrownBy(() -> nodeCommandService.apply(nodeEventParser.parse(
                createEventJson(eventId, WORKSPACE_ID).replace("\"payload\": {",
                        "\"payload\": {\"parentNodeId\": \"" + missingParent + "\","))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-008");

        assertThat(nodeRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from edges", Long.class)).isZero();
        assertThat(processedNodeEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void persistsStableCreateResultWithTheNodeTransaction() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        nodeCommandService.apply(createEvent(eventId));
        assertThat(jdbcTemplate.queryForObject("select to_regclass('node_command_results')", String.class))
                .isNotNull();
        String data = jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId);
        var result = objectMapper.readTree(data);
        assertThat(result.path("version").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(result.path("eventId").asString())).isNotEqualTo(eventId);
        assertThat(result.path("eventType").asString()).isEqualTo("NODE_CREATE_SUCCEEDED");
        assertThat(Instant.parse(result.path("occurredAt").asString())).isEqualTo(NOW);
        assertThat(result.path("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(result.at("/payload/commandEventId").asString()).isEqualTo(eventId.toString());
        assertThat(result.at("/payload/commandEventType").asString()).isEqualTo("NODE_CREATE_REQUESTED");
        assertThat(result.at("/payload/result/nodeId").asString())
                .isEqualTo(nodeRepository.findAll().getFirst().getId().toString());
        assertThat(result.at("/payload/result/nodeVersion").asInt()).isEqualTo(1);
        assertThat(result.path("payload").has("error")).isFalse();
        nodeCommandService.apply(createEvent(eventId));
        assertThat(jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId))
                .isEqualTo(data);
    }

    @Test
    void patchResultRetainsItsCommittedVersionAfterLaterChanges() {
        nodeCommandService.apply(createEvent(UUID.randomUUID()));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        UUID eventId = UUID.randomUUID();
        NodeEventEnvelope command = patchEvent(eventId, nodeId, 1, "{\"title\":\"first\"}");
        nodeCommandService.apply(command);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from node_command_results where command_event_id=?", Long.class, eventId))
                .isEqualTo(1);
        String data = jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId);
        var result = objectMapper.readTree(data);
        assertThat(result.path("eventType").asString()).isEqualTo("NODE_PATCH_SUCCEEDED");
        assertThat(result.at("/payload/result/nodeVersion").asInt()).isEqualTo(2);
        nodeCommandService.apply(patchEvent(UUID.randomUUID(), nodeId, 2, "{\"title\":\"second\"}"));
        nodeCommandService.apply(command);
        assertThat(jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId))
                .isEqualTo(data);
        assertThat(nodeRepository.findById(nodeId).orElseThrow().getVersion()).isEqualTo(3);
    }

    @Test
    void terminalStaleFailurePreservesStructuredDetails() {
        nodeCommandService.apply(createEvent(UUID.randomUUID()));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        UUID eventId = UUID.randomUUID();
        var command = patchEvent(eventId, nodeId, 9, "{\"title\":\"stale\"}");
        var nodeEventWorker = new NodeEventWorker(nodeEventParser,
                new PersistentNodeCommandProcessor(nodeCommandService));
        var failure = nodeEventWorker.process(objectMapper.writeValueAsString(command));
        nodeCommandResultService.recordFailure(null, failure);
        var result = objectMapper.readTree(jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId));
        assertThat(result.path("eventType").asString()).isEqualTo("NODE_PATCH_FAILED");
        assertThat(result.at("/payload/error/code").asString()).isEqualTo("NODE-009");
        assertThat(result.at("/payload/error/details/nodeId").asString()).isEqualTo(nodeId.toString());
        assertThat(result.at("/payload/error/details/expectedVersion").asInt()).isEqualTo(9);
        assertThat(result.at("/payload/error/details/currentVersion").asInt()).isEqualTo(1);
    }

    @Test
    void terminalFailureCannotExecuteAgainAndRetainsOriginalResult() {
        UUID eventId = UUID.randomUUID();
        var command = createEvent(eventId);
        var failure = NodeEventWorkResult.retryableFailure(command, "NODE-011",
                new IllegalStateException("secret database information"));
        nodeCommandResultService.recordFailure(null, failure);
        String original = jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId);
        var nodeEventWorker = new NodeEventWorker(nodeEventParser,
                new PersistentNodeCommandProcessor(nodeCommandService));
        var repeated = nodeEventWorker.process(objectMapper.writeValueAsString(command));
        assertThat(repeated.status()).isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
        assertThat(repeated.errorCode()).isEqualTo("NODE-011");
        assertThat(nodeRepository.count()).isZero();
        assertThat(nodeCommandResultService.recordFailure(null, repeated)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select data from node_command_results where command_event_id=?", String.class, eventId))
                .isEqualTo(original).doesNotContain("secret database information");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"version", "occurredAt", "payload"})
    void invalidEnvelopeWithKnownIdentityStillGetsTerminalResult(String invalidField) {
        UUID eventId = UUID.randomUUID();
        var json = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(createEventJson(eventId, WORKSPACE_ID));
        json.putNull(invalidField);
        String data = objectMapper.writeValueAsString(json);
        var nodeEventWorker = new NodeEventWorker(nodeEventParser,
                new PersistentNodeCommandProcessor(nodeCommandService));
        assertThat(nodeCommandResultService.recordFailure(data, nodeEventWorker.process(data))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from node_command_results where command_event_id=?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(nodeRepository.count()).isZero();
    }

    @Test
    void repeatedTerminalFailureRequeuesTheOriginalResult() {
        UUID eventId = UUID.randomUUID();
        var failure = NodeEventWorkResult.retryableFailure(createEvent(eventId), "NODE-011", null);
        nodeCommandResultService.recordFailure(null, failure);
        jdbcTemplate.update("update node_command_results set published_at=now() where command_event_id=?", eventId);
        assertThat(nodeCommandResultService.recordFailure(null, failure)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select published_at is null from node_command_results where command_event_id=?", Boolean.class, eventId))
                .isTrue();
    }

    @Test
    void legacyProcessedCommandCannotBeReclassifiedAsFailed() {
        UUID eventId = UUID.randomUUID();
        var command = createEvent(eventId);
        nodeCommandService.apply(command);
        jdbcTemplate.update("delete from node_command_results where command_event_id=?", eventId);
        assertThat(nodeCommandResultService.recordFailure(null,
                NodeEventWorkResult.retryableFailure(command, "NODE-011", null))).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from node_command_results", Long.class)).isZero();
    }

    @Test
    void concurrentPatchesCannotBothSucceedWithTheSameExpectedVersion() throws Exception {
        nodeCommandService.apply(createEvent(UUID.randomUUID()));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        var firstApplied = new java.util.concurrent.CountDownLatch(1);
        var releaseCommit = new java.util.concurrent.CountDownLatch(1);
        var transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(platformTransactionManager);
        try (var executorService = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executorService.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                nodeCommandService.apply(patchEvent(UUID.randomUUID(), nodeId, 1, "{\"title\":\"first\"}"));
                firstApplied.countDown();
                try {
                    if (!releaseCommit.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("Commit was not released");
                    }
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
            }));
            assertThat(firstApplied.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var secondCommand = patchEvent(UUID.randomUUID(), nodeId, 1, "{\"title\":\"second\"}");
            var second = executorService.submit(() -> new NodeEventWorker(nodeEventParser,
                    new PersistentNodeCommandProcessor(nodeCommandService))
                    .process(objectMapper.writeValueAsString(secondCommand)));
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (jdbcTemplate.queryForObject("select count(*) from pg_stat_activity "
                        + "where wait_event_type='Lock'", Long.class) == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
            } finally {
                releaseCommit.countDown();
            }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var result = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
            assertThat(result.errorCode()).isEqualTo("NODE-009");
        }
        assertThat(nodeRepository.findById(nodeId).orElseThrow().getVersion()).isEqualTo(2);
    }

    @Test
    void appliesSameEventIdOnlyOnce() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        nodeCommandService.apply(createEvent(eventId));
        nodeCommandService.apply(createEvent(eventId));

        assertThat(nodeRepository.count()).isEqualTo(1);
        assertThat(processedNodeEventRepository.count()).isEqualTo(1);
    }

    @Test
    void mergesPatchDataFieldByFieldAndIncreasesVersion() {
        UUID createEventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        nodeCommandService.apply(createEvent(createEventId));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();

        nodeCommandService.apply(patchEvent(UUID.fromString("44444444-4444-4444-8444-444444444444"), nodeId, 1, """
                {"title": "수정된 회의 요약", "data": {"markdownBody": "AI가 수정한 내용"}}
                """));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select title, version, content->>'markdownBody' as markdown, content->>'color' as color,"
                        + " content->>'jsonBody' as json_body from nodes where node_id=?", nodeId);
        assertThat(row).containsEntry("title", "수정된 회의 요약")
                .containsEntry("version", 2)
                .containsEntry("markdown", "AI가 수정한 내용")
                .containsEntry("color", "#ffffff")
                .containsEntry("json_body", "{\"type\":\"doc\",\"content\":[]}");
    }

    @Test
    void rejectsStaleExpectedVersionWithoutChangingNode() {
        nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        UUID staleEventId = UUID.fromString("44444444-4444-4444-8444-444444444444");

        assertThatThrownBy(() -> nodeCommandService.apply(patchEvent(staleEventId, nodeId, 9,
                "{\"title\": \"충돌 제목\"}")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-009");
        assertThat(jdbcTemplate.queryForObject("select title from nodes where node_id=?", String.class, nodeId))
                .isEqualTo("AI 회의 요약");
        assertThat(processedNodeEventRepository.existsById(staleEventId)).isFalse();
    }

    @Test
    void rejectsPatchForNodeOfAnotherWorkspaceAndSoftDeletedNode() {
        nodeCommandService.apply(createEvent(UUID.fromString("11111111-1111-4111-8111-111111111111")));
        UUID nodeId = nodeRepository.findAll().getFirst().getId();
        UUID otherWorkspaceId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", otherWorkspaceId, "다른 곳");

        assertThatThrownBy(() -> nodeCommandService.apply(patchEvent(
                UUID.fromString("44444444-4444-4444-8444-444444444444"), otherWorkspaceId, nodeId, 1,
                "{\"title\": \"다른 워크스페이스\"}")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-008");

        jdbcTemplate.update("update nodes set deleted_at = now() where node_id=?", nodeId);
        assertThatThrownBy(() -> nodeCommandService.apply(patchEvent(
                UUID.fromString("66666666-6666-4666-8666-666666666666"), nodeId, 1, "{\"title\": \"삭제됨\"}")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsEventForMissingWorkspace() {
        UUID unknownWorkspaceId = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatThrownBy(() -> nodeCommandService.apply(nodeEventParser.parse(createEventJson(
                UUID.fromString("11111111-1111-4111-8111-111111111111"), unknownWorkspaceId))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-007");
        assertThat(nodeRepository.count()).isZero();
    }

    @Test
    void rollsBackProcessedEventWhenNodeChangeFails() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        assertThatThrownBy(() -> nodeCommandService.apply(patchEvent(eventId,
                UUID.fromString("55555555-5555-4555-8555-555555555555"), 1, "{\"title\": \"없는 노드\"}")))
                .isInstanceOf(BusinessException.class);

        assertThat(processedNodeEventRepository.existsById(eventId)).isFalse();
        assertThat(workspaceRepository.existsByIdAndDeletedAtIsNull(WORKSPACE_ID)).isTrue();
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
