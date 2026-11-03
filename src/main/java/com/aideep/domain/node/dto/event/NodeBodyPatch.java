package com.aideep.domain.node.dto.event;

import java.util.UUID;

/**
 * 노드 본문을 바꿔달라고 실시간 서버에 보내는 명령. 이벤트 버스의 {@code NODE_BODY_PATCH} payload다.
 * <p>
 * 본문({@code content.markdownBody})은 {@code nodes.yjs_state}의 Y.Doc이 소스이고 그 문서를 메모리에 들고 있는 쪽은 실시간 서버뿐이라,
 * Spring은 "어느 노드에 무슨 본문"만 보내고 반영은 실시간 서버가 한다. {@code commandEventId}는 재배달된 명령을 실시간 서버가
 * 멱등하게 걸러내는 키다.
 */
public record NodeBodyPatch(UUID workspaceId, UUID nodeId, String markdown, String actorId, UUID commandEventId) {
}
