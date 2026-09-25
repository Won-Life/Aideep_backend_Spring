package com.aideep.domain.node.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.exception.BusinessException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");

    @Test
    void createsNodeWithInitialVersionAndDepth() {
        Node node = Node.create(WORKSPACE_ID, "AI 회의 요약", NodeType.DATA, 100, 200, "{\"color\":\"#ffffff\"}", NOW);

        assertThat(node.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(node.getTitle()).isEqualTo("AI 회의 요약");
        assertThat(node.getNodeType()).isEqualTo(NodeType.DATA);
        assertThat(node.getPositionX()).isEqualTo(100);
        assertThat(node.getPositionY()).isEqualTo(200);
        assertThat(node.getVersion()).isEqualTo(1);
        assertThat(node.getDepth()).isZero();
        assertThat(node.getCreatedAt()).isEqualTo(NOW);
        assertThat(node.getUpdatedAt()).isEqualTo(NOW);
        assertThat(node.getDeletedAt()).isNull();
    }

    @Test
    void rejectsTooLongTitleAndNonFinitePosition() {
        String tooLongTitle = "가".repeat(Node.MAX_TITLE_LENGTH + 1);

        assertThatThrownBy(() -> Node.create(WORKSPACE_ID, tooLongTitle, NodeType.DATA, 0, 0, "{}", NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(NodeError.INVALID_PAYLOAD));
        assertThatThrownBy(() -> Node.create(WORKSPACE_ID, "제목", NodeType.DATA, Double.NaN, 0, "{}", NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(NodeError.INVALID_PAYLOAD));
        assertThatThrownBy(
                () -> Node.create(WORKSPACE_ID, "제목", NodeType.DATA, 0, Double.POSITIVE_INFINITY, "{}", NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(NodeError.INVALID_PAYLOAD));
    }

    @Test
    void appliesOnlyProvidedFieldsAndIncreasesVersion() {
        Node node = Node.create(WORKSPACE_ID, "원래 제목", NodeType.DATA, 10, 20, "{\"color\":\"#ffffff\"}", NOW);
        Instant later = NOW.plusSeconds(60);

        node.applyPatch("수정된 제목", null, null, 1, later);

        assertThat(node.getTitle()).isEqualTo("수정된 제목");
        assertThat(node.getNodeType()).isEqualTo(NodeType.DATA);
        assertThat(node.getContent()).isEqualTo("{\"color\":\"#ffffff\"}");
        assertThat(node.getVersion()).isEqualTo(2);
        assertThat(node.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void rejectsPatchWithStaleExpectedVersion() {
        Node node = Node.create(WORKSPACE_ID, "제목", NodeType.DATA, 10, 20, "{}", NOW);
        node.applyPatch("두 번째 제목", null, null, 1, NOW);

        assertThatThrownBy(() -> node.applyPatch("세 번째 제목", null, null, 1, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(NodeError.STALE_NODE_VERSION);
                    assertThat(exception.getData()).isEqualTo(Map.of(
                            "nodeId", node.getId(), "expectedVersion", 1, "currentVersion", 2));
                });
        assertThat(node.getTitle()).isEqualTo("두 번째 제목");
        assertThat(node.getVersion()).isEqualTo(2);
    }

    @Test
    void marksDeletedAtAndUpdatesTimestamp() {
        Node node = Node.create(WORKSPACE_ID, "제목", NodeType.DATA, 0, 0, "{}", NOW);
        Instant later = NOW.plusSeconds(120);

        node.delete(later);

        assertThat(node.getDeletedAt()).isEqualTo(later);
        assertThat(node.getUpdatedAt()).isEqualTo(later);
    }
}
