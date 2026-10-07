package com.aideep.domain.meeting.exception;

import com.aideep.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MeetingError implements ErrorCode {

    BOT_INVITATION_REJECTED(HttpStatus.BAD_REQUEST, "MEETING-001", "회의 봇을 초대할 수 없습니다."),
    RECALL_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MEETING-002",
            "회의 봇 서비스에 일시적으로 연결할 수 없습니다."),
    RECALL_RESPONSE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "MEETING-003",
            "회의 봇 서비스의 응답이 올바르지 않습니다."),
    WEBHOOK_SIGNATURE_INVALID(
            HttpStatus.UNAUTHORIZED,
            "MEETING-004",
            "회의 봇 웹훅 서명이 올바르지 않습니다."),
    WORKSPACE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "MEETING-005",
            "워크스페이스를 찾을 수 없습니다."),
    NODE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "MEETING-006",
            "워크스페이스에서 회의 노드를 찾을 수 없습니다."),
    BOT_ALREADY_INVITED(
            HttpStatus.CONFLICT,
            "MEETING-007",
            "이미 봇이 참여 중인 회의입니다.");

    private final HttpStatus status;
    private final String code;
    private final String reason;
}
