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
    void locksAndUnlocksOnlyForTheOwningMeeting() {
        UUID meetingId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        UUID otherMeetingId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        Node node = Node.create(WORKSPACE_ID, "회의 노드", NodeType.DATA, 0, 0, "{}", NOW);
        Instant locked = NOW.plusSeconds(10);

        assertThat(node.isLocked()).isFalse();
        assertThat(node.lockForMeeting(meetingId, locked)).isTrue();
        assertThat(node.isLocked()).isTrue();
        assertThat(node.getLockedByMeetingId()).isEqualTo(meetingId);
        assertThat(node.getUpdatedAt()).isEqualTo(locked);

        // 이미 잠긴 노드는 다른 회의가 가져가지 못한다. 가져가면 먼저 끝난 회의가 진행 중인 회의의 잠금을 풀어버린다.
        assertThat(node.lockForMeeting(otherMeetingId, NOW.plusSeconds(20))).isFalse();
        assertThat(node.getLockedByMeetingId()).isEqualTo(meetingId);
        assertThat(node.getUpdatedAt()).isEqualTo(locked);

        assertThat(node.unlockForMeeting(otherMeetingId, NOW.plusSeconds(30))).isFalse();
        assertThat(node.getLockedByMeetingId()).isEqualTo(meetingId);

        Instant unlocked = NOW.plusSeconds(40);
        assertThat(node.unlockForMeeting(meetingId, unlocked)).isTrue();
        assertThat(node.isLocked()).isFalse();
        assertThat(node.getLockedByMeetingId()).isNull();
        assertThat(node.getUpdatedAt()).isEqualTo(unlocked);
        // 이미 풀린 노드를 다시 풀어도 시각을 바꾸지 않는다.
        assertThat(node.unlockForMeeting(meetingId, NOW.plusSeconds(50))).isFalse();
        assertThat(node.getUpdatedAt()).isEqualTo(unlocked);
    }

    /**
     * 잠금은 사용자 직접 수정만 막는 표시다. AI 서버의 노드 명령은 잠긴 노드에도 그대로 반영된다.
     */
    @Test
    void stillAppliesPatchWhileLocked() {
        UUID meetingId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        Node node = Node.create(WORKSPACE_ID, "회의 노드", NodeType.DATA, 0, 0, "{}", NOW);
        node.lockForMeeting(meetingId, NOW);

        node.applyPatch("AI가 쓴 제목", null, null, 1, NOW.plusSeconds(10));

        assertThat(node.getTitle()).isEqualTo("AI가 쓴 제목");
        assertThat(node.getVersion()).isEqualTo(2);
        assertThat(node.getLockedByMeetingId()).isEqualTo(meetingId);
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
