package com.aideep.domain.node.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.entity.Edge;

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

/** edges 테이블의 실제 컬럼명, 외래 키, 삭제 조건 매핑을 검증한다. */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class EdgeRepositoryIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID ROOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CHILD_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID GRANDCHILD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
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
    private EdgeRepository edgeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate edges, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "graph");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", OTHER_WORKSPACE_ID, "other");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)",
                ROOT_ID, WORKSPACE_ID, "회의 노드");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)",
                CHILD_ID, WORKSPACE_ID, "안건");
        jdbcTemplate.update("insert into nodes(node_id, workspace_id, title) values (?,?,?)",
                GRANDCHILD_ID, WORKSPACE_ID, "세부 안건");
    }

    @Test
    void persistsEdgeWithActualColumnNames() {
        Edge saved = edgeRepository.saveAndFlush(
                Edge.create(WORKSPACE_ID, ROOT_ID, CHILD_ID, "right", "left", NOW));

        Edge found = edgeRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getSourceId()).isEqualTo(ROOT_ID);
        assertThat(found.getTargetId()).isEqualTo(CHILD_ID);
        assertThat(found.getSourceHandle()).isEqualTo("right");
        assertThat(found.getTargetHandle()).isEqualTo("left");
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select source_handle from edges where edge_id=?", String.class,
                saved.getId())).isEqualTo("right");
        assertThat(jdbcTemplate.queryForObject("select version from edges where edge_id=?", Integer.class,
                saved.getId())).isEqualTo(1);
    }

    @Test
    void findsOutgoingEdgesOfSeveralSourcesAtOnce() {
        edgeRepository.saveAndFlush(Edge.create(WORKSPACE_ID, ROOT_ID, CHILD_ID, null, null, NOW));
        edgeRepository.saveAndFlush(Edge.create(WORKSPACE_ID, CHILD_ID, GRANDCHILD_ID, null, null, NOW));

        List<Edge> edges = edgeRepository.findByWorkspaceIdAndSourceIdInAndDeletedAtIsNull(
                WORKSPACE_ID, List.of(ROOT_ID, CHILD_ID));

        assertThat(edges).extracting(Edge::getTargetId).containsExactlyInAnyOrder(CHILD_ID, GRANDCHILD_ID);
    }

    @Test
    void excludesDeletedEdges() {
        Edge edge = Edge.create(WORKSPACE_ID, ROOT_ID, CHILD_ID, null, null, NOW);
        edge.delete(NOW.plusSeconds(10));
        edgeRepository.saveAndFlush(edge);

        assertThat(edgeRepository.findByWorkspaceIdAndSourceIdInAndDeletedAtIsNull(WORKSPACE_ID, List.of(ROOT_ID)))
                .isEmpty();
    }

    @Test
    void excludesEdgesOfAnotherWorkspace() {
        edgeRepository.saveAndFlush(Edge.create(WORKSPACE_ID, ROOT_ID, CHILD_ID, null, null, NOW));

        assertThat(edgeRepository
                .findByWorkspaceIdAndSourceIdInAndDeletedAtIsNull(OTHER_WORKSPACE_ID, List.of(ROOT_ID))).isEmpty();
    }

    @Test
    void rejectsEdgeReferencingMissingNode() {
        Edge edge = Edge.create(WORKSPACE_ID, ROOT_ID, UUID.fromString("77777777-7777-4777-8777-777777777777"),
                null, null, NOW);

        assertThatThrownBy(() -> edgeRepository.saveAndFlush(edge))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("edges_target_id_fkey");
    }
}
