package com.aideep.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.node.exception.NodeError;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md의 도메인 오류 코드 규칙(대문자 도메인명-고유 숫자, 예: NODE-001)을 모든 도메인 ErrorCode enum에 대해 검증한다.
 * GlobalErrorCode는 NestJS와 공유하는 별도 계약(COMMON/VALID)이라 이 규칙의 대상이 아니다.
 */
class DomainErrorCodeFormatTest {
    private static final Pattern DOMAIN_CODE_PATTERN = Pattern.compile("^[A-Z]+-\\d{3}$");

    @Test
    void authErrorCodesFollowDomainNumberFormat() {
        assertCodesFollowConvention(AuthError.values(), "AUTH");
    }

    @Test
    void meetingErrorCodesFollowDomainNumberFormat() {
        assertCodesFollowConvention(MeetingError.values(), "MEETING");
    }

    @Test
    void nodeErrorCodesFollowDomainNumberFormat() {
        assertCodesFollowConvention(NodeError.values(), "NODE");
    }

    private void assertCodesFollowConvention(ErrorCode[] errorCodes, String domainPrefix) {
        List<String> codes = List.of(errorCodes).stream().map(ErrorCode::getCode).toList();
        assertThat(codes).as("모든 코드는 %s-NNN 형식이어야 한다", domainPrefix)
                .allSatisfy(code -> assertThat(code).matches(DOMAIN_CODE_PATTERN));
        assertThat(codes).as("모든 코드는 %s- 로 시작해야 한다", domainPrefix)
                .allSatisfy(code -> assertThat(code).startsWith(domainPrefix + "-"));
        assertThat(codes).as("코드는 중복되지 않아야 한다").doesNotHaveDuplicates();
    }
}
