package com.aideep.global.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반을 나타내는 예외. GlobalExceptionHandler가 공통 FAIL 응답으로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 응답의 error.data에 그대로 담기는 부가 정보. 없으면 null.
     */
    private final Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, Object data) {
        super(errorCode.getReason());
        this.errorCode = errorCode;
        this.data = data;
    }
}
