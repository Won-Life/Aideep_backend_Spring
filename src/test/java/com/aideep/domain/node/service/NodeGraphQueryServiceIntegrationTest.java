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
import java.util.List;
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

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Import(NodeGraphQueryService.class)
class NodeGraphQueryServiceIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
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
    private NodeGraphQueryService nodeGraphQueryService;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private EdgeRepository edgeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate edges, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "graph");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", OTHER_WORKSPACE_ID, "other");
    }

    @Test
    void collectsTheRequestedNodeAndEveryDescendant() {
        UUID root = node("회의 노드", 0);
        UUID child = node("안건", 1);
        UUID grandchild = node("세부 안건", 2);
        UUID greatGrandchild = node("결정", 3);
        UUID unrelated = node("다른 노드", 4);
        edge(root, child);
        edge(child, grandchild);
        edge(grandchild, greatGrandchild);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId)
                .containsExactly(root, child, grandchild, greatGrandchild);
        assertThat(graph.nodes()).extracting(Node::getId).doesNotContain(unrelated);
        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.edges()).extracting(Edge::getSourceId).containsExactly(root, child, grandchild);
    }

    /**
     * 부모 방향 엣지는 따라가지 않는다. 하위 그래프만 필요하다.
     */
    @Test
    void doesNotWalkIncomingEdges() {
        UUID parent = node("상위", 0);
        UUID root = node("회의 노드", 1);
        UUID child = node("안건", 2);
        edge(parent, root);
        edge(root, child);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root, child);
        assertThat(graph.edges()).extracting(Edge::getSourceId).containsExactly(root);
    }

    @Test
    void returnsOnlyTheRequestedNodeWhenItHasNoEdges() {
        UUID root = node("회의 노드", 0);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root);
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void terminatesOnCyclicEdges() {
        UUID root = node("회의 노드", 0);
        UUID child = node("안건", 1);
        UUID grandchild = node("세부 안건", 2);
        edge(root, child);
        edge(child, grandchild);
        edge(grandchild, root);
        edge(child, child);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root, child, grandchild);
        assertThat(graph.edges()).hasSize(4);
    }

    @Test
    void stopsAtDeletedNodesAndEdges() {
        UUID root = node("회의 노드", 0);
        UUID deletedChild = node("삭제된 안건", 1);
        UUID behindDeletedChild = node("삭제된 안건의 하위", 2);
        UUID childBehindDeletedEdge = node("삭제된 엣지의 하위", 3);
        edge(root, deletedChild);
        edge(deletedChild, behindDeletedChild);
        deleteEdge(edge(root, childBehindDeletedEdge));
        deleteNode(deletedChild);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root);
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void doesNotCrossWorkspaceBoundaries() {
        UUID root = node("회의 노드", 0);
        UUID foreignChild = nodeIn(OTHER_WORKSPACE_ID, "다른 워크스페이스 노드", 1);
        java.time.OffsetDateTime createdAt = NOW.atOffset(java.time.ZoneOffset.UTC);
        jdbcTemplate.update("""
                insert into edges(edge_id, workspace_id, source_id, target_id, created_at, updated_at)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), WORKSPACE_ID, root, foreignChild, createdAt, createdAt);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root);
        // nodes에 없는 노드를 가리키는 엣지를 응답에 남기지 않는다.
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void rejectsNodeOfAnotherWorkspace() {
        UUID foreignNode = nodeIn(OTHER_WORKSPACE_ID, "다른 워크스페이스 노드", 0);

        assertThatThrownBy(() -> nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, foreignNode))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(NodeError.NODE_NOT_FOUND);
    }

    @Test
    void rejectsMissingOrDeletedNode() {
        UUID deleted = node("삭제된 노드", 0);
        deleteNode(deleted);

        assertThatThrownBy(() -> nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, deleted))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(NodeError.NODE_NOT_FOUND);
        assertThatThrownBy(() -> nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(NodeError.NODE_NOT_FOUND);
    }

    /**
     * 같은 노드로 가는 경로가 여럿이어도 노드는 한 번만 담긴다.
     */
    @Test
    void returnsDiamondShapedGraphWithoutDuplicates() {
        UUID root = node("회의 노드", 0);
        UUID left = node("왼쪽", 1);
        UUID right = node("오른쪽", 2);
        UUID join = node("합류", 3);
        edge(root, left);
        edge(root, right);
        edge(left, join);
        edge(right, join);

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).extracting(Node::getId).containsExactly(root, left, right, join);
        assertThat(graph.edges()).hasSize(4);
    }

    private UUID node(String title, int order) {
        return nodeIn(WORKSPACE_ID, title, order);
    }

    private UUID nodeIn(UUID workspaceId, String title, int order) {
        Node node = Node.create(workspaceId, title, NodeType.DATA, order, order, "{}", NOW.plusSeconds(order));
        return nodeRepository.saveAndFlush(node).getId();
    }

    private UUID edge(UUID sourceId, UUID targetId) {
        return edgeRepository
                .saveAndFlush(Edge.create(WORKSPACE_ID, sourceId, targetId, null, null, NOW))
                .getId();
    }

    private void deleteNode(UUID nodeId) {
        Node node = nodeRepository.findById(nodeId).orElseThrow();
        node.delete(NOW.plusSeconds(100));
        nodeRepository.saveAndFlush(node);
    }

    private void deleteEdge(UUID edgeId) {
        Edge edge = edgeRepository.findById(edgeId).orElseThrow();
        edge.delete(NOW.plusSeconds(100));
        edgeRepository.saveAndFlush(edge);
    }

    @Test
    void doesNotGrowQueryCountWithDepth() {
        UUID root = node("회의 노드", 0);
        UUID previous = root;
        for (int depth = 1; depth <= 50; depth++) {
            UUID next = node("깊이 " + depth, depth);
            edge(previous, next);
            previous = next;
        }

        NodeGraphQueryService.NodeGraph graph = nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root);

        assertThat(graph.nodes()).hasSize(51);
        assertThat(graph.edges()).hasSize(50);
        assertThat(List.copyOf(graph.nodes()).getFirst().getId()).isEqualTo(root);
    }
}
