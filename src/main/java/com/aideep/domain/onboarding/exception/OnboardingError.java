package com.aideep.domain.onboarding.exception;

import com.aideep.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OnboardingError implements ErrorCode {

    REQUIRED_TERM_NOT_AGREED(HttpStatus.BAD_REQUEST, "ONBOARDING-001", "필수 약관에 동의해야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String reason;
}
