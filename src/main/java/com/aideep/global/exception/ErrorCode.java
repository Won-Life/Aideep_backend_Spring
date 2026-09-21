package com.aideep.global.exception;

import org.springframework.http.HttpStatus;

/**
 * NestJS 서버와 공유하는 에러 코드 계약. 도메인별 에러 코드는 각 도메인 패키지에서 이 인터페이스를 구현하는 enum으로 정의한다.
 */
public interface ErrorCode {

    String getCode();

    String getReason();

    HttpStatus getStatus();
}
