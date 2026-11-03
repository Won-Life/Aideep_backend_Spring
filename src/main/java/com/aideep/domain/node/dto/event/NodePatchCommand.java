package com.aideep.domain.node.dto.event;

import com.aideep.domain.node.entity.NodeType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

public record NodePatchCommand(UUID nodeId, int expectedVersion, String title, NodeType nodeType, ObjectNode data) {

    public static final String MARKDOWN_BODY_FIELD = "markdownBody";

    public boolean hasNoChange() {
        return title == null && nodeType == null && data == null;
    }

    /**
     * 본문으로 다룰 markdown 문자열. 본문의 소스는 {@code nodes.yjs_state}라서 DB에 직접 쓰지 않고 실시간 서버에 맡긴다.
     * 문자열이 아닌 값은 본문으로 보지 않고 일반 data 필드로 남긴다.
     */
    public String markdownBody() {
        if (data == null) {
            return null;
        }
        JsonNode value = data.get(MARKDOWN_BODY_FIELD);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** 본문을 뺀 나머지 data. 남는 필드가 없으면 {@code null}을 돌려줘 content에 쓸 변경이 없음을 나타낸다. */
    public ObjectNode dataWithoutMarkdownBody() {
        if (data == null || markdownBody() == null) {
            return data;
        }
        ObjectNode remaining = data.deepCopy();
        remaining.remove(MARKDOWN_BODY_FIELD);
        return remaining.isEmpty() ? null : remaining;
    }
}
