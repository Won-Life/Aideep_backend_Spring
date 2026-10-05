package com.aideep.domain.onboarding.entity;

/**
 * 온보딩 3단계에서 고르는 주 회의처. 복수 선택이다.
 * 봇 연동용 {@code Bottype}과 값 집합이 다르므로(대면·기타 포함, DISCORD 미노출) 별도 타입으로 둔다.
 */
public enum MeetingPlatform {
    ZOOM,
    GOOGLE_MEET,
    OFFLINE,
    OTHER
}
