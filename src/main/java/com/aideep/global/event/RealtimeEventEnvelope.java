package com.aideep.global.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code aideep-ws}가 구독하는 실시간 이벤트 버스의 공통 envelope.
 * <p>
 * 백엔드는 "무슨 일이 일어났다"는 사실만 발행하고 room 라우팅은 WS 서버가 한다. 따라서 envelope에 전송 지시를 넣지 않으며, 이벤트 종류는
 * {@code payload.type}으로만 구분한다. 노드 도메인과 회의 도메인이 같은 채널을 공유하므로 global에 둔다.
 */
public record RealtimeEventEnvelope<P>(int v, String kind, UUID messageId, Instant publishedAt, String origin,
                                       P payload) {

    public static final String CHANNEL = "aideep.realtime.v1";
    public static final int VERSION = 1;
    public static final String WORKSPACE_EVENT = "WORKSPACE_EVENT";
    public static final String NODE_BODY_PATCH = "NODE_BODY_PATCH";
    public static final String ORIGIN = "spring-api";

    public static <P> RealtimeEventEnvelope<P> workspaceEvent(UUID messageId, Instant publishedAt, P payload) {
        return new RealtimeEventEnvelope<>(VERSION, WORKSPACE_EVENT, messageId, publishedAt, ORIGIN, payload);
    }

    /**
     * 클라이언트로 그대로 중계되는 {@link #WORKSPACE_EVENT}와 달리, 실시간 서버가 받아서 Y.Doc을 직접 바꿔야 하는 명령이다.
     * 그래서 kind를 따로 둔다.
     */
    public static <P> RealtimeEventEnvelope<P> nodeBodyPatch(UUID messageId, Instant publishedAt, P payload) {
        return new RealtimeEventEnvelope<>(VERSION, NODE_BODY_PATCH, messageId, publishedAt, ORIGIN, payload);
    }
}
