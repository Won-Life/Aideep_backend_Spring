package com.aideep.domain.user.exception;

import com.aideep.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserError implements ErrorCode {
    UserNotFound(HttpStatus.NOT_FOUND, "USER1", "유저가 없음");

    private final HttpStatus status;
    private final String code;
    private final String reason;
}
