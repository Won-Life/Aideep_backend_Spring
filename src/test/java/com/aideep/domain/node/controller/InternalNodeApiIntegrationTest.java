package com.aideep.domain.node.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.node.entity.Edge;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.entity.NodeType;
import com.aideep.domain.node.repository.EdgeRepository;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.domain.node.service.NodeGraphQueryService;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;

import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL에 저장된 노드·엣지가 내부 API 응답 계약대로 나가는지 확인한다. */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(NodeGraphQueryService.class)
class InternalNodeApiIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final String PATH = "/v1/aideep/api/internal/workspaces/{workspaceId}/nodes/{nodeId}";

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate edges, nodes, users_workspaces, workspaces, users cascade");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", WORKSPACE_ID, "graph");
        jdbcTemplate.update("insert into workspaces(workspace_id, title) values (?,?)", OTHER_WORKSPACE_ID, "other");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalNodeController(nodeGraphQueryService, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void returnsStoredJsonbContentAsAnObject() throws Exception {
        Node root = nodeRepository.saveAndFlush(Node.create(WORKSPACE_ID, "주간 회의", NodeType.DATA, 120, 40,
                "{\"dataType\":\"MARKDOWN\",\"markdownBody\":\"# 회의 요약\",\"color\":\"#ffffff\"}", NOW));
        Node child = nodeRepository.saveAndFlush(
                Node.create(WORKSPACE_ID, "안건", NodeType.RESOURCE, 200, 80, "{}", NOW.plusSeconds(1)));
        edgeRepository.saveAndFlush(
                Edge.create(WORKSPACE_ID, root.getId(), child.getId(), "right", "left", NOW.plusSeconds(2)));

        mockMvc.perform(get(PATH, WORKSPACE_ID, root.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success.nodes", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.success.nodes[0].nodeId").value(root.getId().toString()))
                .andExpect(jsonPath("$.success.nodes[0].data.markdownBody").value("# 회의 요약"))
                .andExpect(jsonPath("$.success.nodes[0].data.color").value("#ffffff"))
                .andExpect(jsonPath("$.success.nodes[1].nodeType").value("RESOURCE"))
                .andExpect(jsonPath("$.success.edges", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.success.edges[0].source").value(root.getId().toString()))
                .andExpect(jsonPath("$.success.edges[0].target").value(child.getId().toString()));
    }

    @Test
    void returnsNotFoundForNodeOfAnotherWorkspace() throws Exception {
        Node foreign = nodeRepository.saveAndFlush(
                Node.create(OTHER_WORKSPACE_ID, "다른 워크스페이스", NodeType.DATA, 0, 0, "{}", NOW));

        mockMvc.perform(get(PATH, WORKSPACE_ID, foreign.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.errorCode").value("NODE-008"));
    }
}
