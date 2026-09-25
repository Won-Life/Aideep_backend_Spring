package com.aideep.domain.node.exception;

import com.aideep.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 노드 명령과 Redis 소비 경계에서 사용하는 오류 코드. */
@Getter
@RequiredArgsConstructor
public enum NodeError implements ErrorCode {
    INVALID_JSON(HttpStatus.BAD_REQUEST, "NODE-001", "노드 이벤트 JSON 형식이 올바르지 않습니다."),
    INVALID_ENVELOPE(HttpStatus.BAD_REQUEST, "NODE-002", "노드 이벤트 envelope가 올바르지 않습니다."),
    UNSUPPORTED_VERSION(HttpStatus.BAD_REQUEST, "NODE-003", "지원하지 않는 노드 이벤트 버전입니다."),
    UNSUPPORTED_EVENT_TYPE(HttpStatus.BAD_REQUEST, "NODE-004", "지원하지 않는 노드 이벤트 유형입니다."),
    INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "NODE-005", "노드 명령 payload가 올바르지 않습니다."),
    UNSUPPORTED_NODE_TYPE(HttpStatus.BAD_REQUEST, "NODE-006", "지원하지 않는 노드 유형입니다."),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "NODE-007", "워크스페이스를 찾을 수 없습니다."),
    NODE_NOT_FOUND(HttpStatus.NOT_FOUND, "NODE-008", "워크스페이스에서 노드를 찾을 수 없습니다."),
    STALE_NODE_VERSION(HttpStatus.CONFLICT, "NODE-009", "노드 버전이 일치하지 않습니다."),
    INVALID_STREAM_ENTRY(HttpStatus.BAD_REQUEST, "NODE-010", "노드 스트림 항목에 data가 없습니다."),
    PROCESSOR_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "NODE-011", "노드 명령 처리에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String reason;
}
