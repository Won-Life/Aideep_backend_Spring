package com.aideep.domain.node.controller;

import com.aideep.domain.node.dto.response.NodeGraphResponse;
import com.aideep.domain.node.service.NodeGraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * AI 서버가 회의 노드의 맥락을 가져가는 내부 조회 경계.
 * <p>
 * 사용자 JWT가 아니라 {@code X-Internal-Key}로 인증한다(전용 SecurityFilterChain). 따라서 사용자 권한 검사는 하지 않고, 노드가 요청
 * 워크스페이스에 속하는지만 확인한다.
 */
@RestController
@Tag(name = "내부 노드 컨트롤러")
@RequestMapping("/v1/aideep/api/internal")
public class InternalNodeController {

    private final NodeGraphQueryService nodeGraphQueryService;
    private final ObjectMapper objectMapper;

    public InternalNodeController(NodeGraphQueryService nodeGraphQueryService, ObjectMapper objectMapper) {
        this.nodeGraphQueryService = nodeGraphQueryService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/workspaces/{workspaceId}/nodes/{nodeId}")
    @Operation(summary = "회의 노드와 하위 노드·엣지 조회")
    public NodeGraphResponse findDescendantGraph(@PathVariable UUID workspaceId, @PathVariable UUID nodeId) {
        NodeGraphQueryService.NodeGraph nodeGraph = nodeGraphQueryService.findDescendantGraph(workspaceId, nodeId);
        return NodeGraphResponse.of(nodeGraph.nodes(), nodeGraph.edges(), objectMapper);
    }
}
