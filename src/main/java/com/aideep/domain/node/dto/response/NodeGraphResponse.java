package com.aideep.domain.node.dto.response;

import com.aideep.domain.node.entity.Edge;
import com.aideep.domain.node.entity.Node;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 내부 API의 하위 그래프 응답. 노드 객체는 기존 aideep-ws WORKSPACE_EVENT의 노드 스냅샷과 같은 필드를 쓰지만, 이벤트 DTO를 REST 계약과
 * 공유하지 않기 위해 별도로 둔다.
 */
public record NodeGraphResponse(List<NodeResponse> nodes, List<EdgeResponse> edges) {

    public static NodeGraphResponse of(List<Node> nodes, List<Edge> edges, ObjectMapper objectMapper) {
        return new NodeGraphResponse(
                nodes.stream().map(node -> NodeResponse.of(node, objectMapper)).toList(),
                edges.stream().map(EdgeResponse::of).toList());
    }

    public record NodeResponse(UUID nodeId, String title, String nodeType, Position position,
                               Map<String, Object> data, Instant createdAt) {

        /**
         * Jackson 3의 {@code ObjectNode}를 그대로 노출하면 springdoc(Jackson 2 기반 introspection)이 {@code setAll} 오버로드를
         * 구분하지 못해 {@code /v3/api-docs} 생성이 실패한다. 직렬화 결과가 같은 Map으로 담는다.
         */
        private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {
        };

        static NodeResponse of(Node node, ObjectMapper objectMapper) {
            return new NodeResponse(node.getId(), node.getTitle(), node.getNodeType().name(),
                    new Position(node.getPositionX(), node.getPositionY()), readData(node, objectMapper),
                    node.getCreatedAt());
        }

        /**
         * {@code nodes.content}는 노드 생성 명령의 data를 그대로 저장한 jsonb다. 문자열로 흘리지 않고 객체로 전달한다.
         */
        private static Map<String, Object> readData(Node node, ObjectMapper objectMapper) {
            if (node.getContent() == null || node.getContent().isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(node.getContent(), DATA_TYPE);
        }
    }

    public record Position(Double x, Double y) {
    }

    public record EdgeResponse(UUID edgeId, UUID source, UUID target, String sourceHandle, String targetHandle,
                               Instant createdAt) {

        static EdgeResponse of(Edge edge) {
            return new EdgeResponse(edge.getId(), edge.getSourceId(), edge.getTargetId(), edge.getSourceHandle(),
                    edge.getTargetHandle(), edge.getCreatedAt());
        }
    }
}
