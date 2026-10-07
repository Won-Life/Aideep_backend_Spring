package com.aideep.domain.node.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.node.entity.Edge;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.entity.NodeType;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.service.NodeGraphQueryService;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InternalNodeControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-10-05T10:00:00Z");

    private NodeGraphQueryService nodeGraphQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        nodeGraphQueryService = mock(NodeGraphQueryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalNodeController(nodeGraphQueryService, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void returnsNodesAndEdgesInsideTheCommonEnvelope() throws Exception {
        Node root = Node.create(WORKSPACE_ID, "주간 회의", NodeType.DATA, 120, 40,
                "{\"dataType\":\"MARKDOWN\",\"markdownBody\":\"# 회의\"}", CREATED_AT);
        Node child = Node.create(WORKSPACE_ID, "안건", NodeType.DATA, 200, 80, null, CREATED_AT.plusSeconds(1));
        Edge edge = Edge.create(WORKSPACE_ID, root.getId(), child.getId(), "right", null, CREATED_AT.plusSeconds(2));
        when(nodeGraphQueryService.findDescendantGraph(WORKSPACE_ID, root.getId()))
                .thenReturn(new NodeGraphQueryService.NodeGraph(List.of(root, child), List.of(edge)));

        mockMvc.perform(get("/v1/aideep/api/internal/workspaces/{workspaceId}/nodes/{nodeId}",
                        WORKSPACE_ID, root.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.success.nodes", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.success.nodes[0].nodeId").value(root.getId().toString()))
                .andExpect(jsonPath("$.success.nodes[0].title").value("주간 회의"))
                .andExpect(jsonPath("$.success.nodes[0].nodeType").value("DATA"))
                .andExpect(jsonPath("$.success.nodes[0].position.x").value(120.0))
                .andExpect(jsonPath("$.success.nodes[0].position.y").value(40.0))
                .andExpect(jsonPath("$.success.nodes[0].data.dataType").value("MARKDOWN"))
                .andExpect(jsonPath("$.success.nodes[0].createdAt").value("2026-10-05T10:00:00Z"))
                // content가 비어 있어도 data는 객체로 내보낸다.
                .andExpect(jsonPath("$.success.nodes[1].data").isMap())
                .andExpect(jsonPath("$.success.edges", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.success.edges[0].edgeId").value(edge.getId().toString()))
                .andExpect(jsonPath("$.success.edges[0].source").value(root.getId().toString()))
                .andExpect(jsonPath("$.success.edges[0].target").value(child.getId().toString()))
                .andExpect(jsonPath("$.success.edges[0].sourceHandle").value("right"))
                .andExpect(jsonPath("$.success.edges[0].targetHandle").value(Matchers.nullValue()));
    }

    @Test
    void returnsNodeNotFoundWhenTheNodeIsNotInTheWorkspace() throws Exception {
        UUID nodeId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        doThrow(new BusinessException(NodeError.NODE_NOT_FOUND))
                .when(nodeGraphQueryService).findDescendantGraph(any(UUID.class), any(UUID.class));

        mockMvc.perform(get("/v1/aideep/api/internal/workspaces/{workspaceId}/nodes/{nodeId}",
                        WORKSPACE_ID, nodeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("NODE-008"))
                .andExpect(jsonPath("$.success").value(Matchers.nullValue()));
    }

    @Test
    void rejectsMalformedIdentifiers() throws Exception {
        mockMvc.perform(get("/v1/aideep/api/internal/workspaces/not-a-uuid/nodes/{nodeId}", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }
}
