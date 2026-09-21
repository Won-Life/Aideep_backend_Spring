package com.aideep.domain.auth.exception;

import com.aideep.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthError implements ErrorCode {
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_REVOKED", "만료된 토큰입니다."), MASTER_TOKEN_REVOKED(
            HttpStatus.UNAUTHORIZED, "AUTH_MASTER_TOKEN_REVOKED", "만료/폐기된 마스터 토큰입니다."), VERIFICATION_CODE_EXPIRED(
            HttpStatus.UNAUTHORIZED, "AUTH_VERIFICATION_CODE_EXPIRED",
            "인증번호가 만료되었습니다."), VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.UNAUTHORIZED,
            "AUTH_VERIFICATION_ATTEMPTS_EXCEEDED", "인증 횟수를 초과했습니다. 다시 요청해주세요."), VERIFICATION_CODE_MISMATCH(
            HttpStatus.UNAUTHORIZED, "AUTH_VERIFICATION_CODE_MISMATCH", "인증번호가 일치하지 않습니다."), OAUTH_STATE_MISSING(
            HttpStatus.UNAUTHORIZED, "AUTH_OAUTH_STATE_MISSING", "state 누락"), OAUTH_STATE_INVALID(
            HttpStatus.UNAUTHORIZED, "AUTH_OAUTH_STATE_INVALID", "유효하지 않은 state"), GOOGLE_AUTHENTICATION_FAILED(
            HttpStatus.UNAUTHORIZED, "AUTH_GOOGLE_AUTHENTICATION_FAILED",
            "Google 인증에 실패했습니다."), GOOGLE_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT,
            "AUTH_GOOGLE_ACCOUNT_ALREADY_LINKED", "이미 다른 계정에 연동된 Google 계정입니다."), USER_NOT_FOUND(
            HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."), OAUTH_EMAIL_CONFLICT(
            HttpStatus.CONFLICT, "AUTH_OAUTH_EMAIL_CONFLICT",
            "이미 가입된 이메일입니다. 이메일/비밀번호 로그인 후 계정을 연동하세요."), SIGNUP_TICKET_INVALID(HttpStatus.UNAUTHORIZED,
            "AUTH_SIGNUP_TICKET_INVALID", "유효하지 않거나 만료된 ticket 입니다."), OAUTH_SIGNUP_CONFLICT(HttpStatus.CONFLICT,
            "AUTH_OAUTH_SIGNUP_CONFLICT", "이미 가입된 이메일 또는 OAuth 계정입니다."), GOOGLE_CODE_MISSING(HttpStatus.UNAUTHORIZED,
            "AUTH_GOOGLE_CODE_MISSING", "Google 인증 코드가 누락되었습니다."), GOOGLE_EMAIL_UNVERIFIED(HttpStatus.UNAUTHORIZED,
            "AUTH_GOOGLE_EMAIL_UNVERIFIED", "Google 이메일 인증이 완료되지 않았습니다."), GOOGLE_PROFILE_INVALID(
            HttpStatus.UNAUTHORIZED, "AUTH_GOOGLE_PROFILE_INVALID", "Google 사용자 정보가 유효하지 않습니다."), LOGIN_USER_NOT_FOUND(
            HttpStatus.UNAUTHORIZED, "AUTH_LOGIN_USER_NOT_FOUND", "존재하지 않는 아이디 입니다"), PASSWORD_MISMATCH(
            HttpStatus.UNAUTHORIZED, "AUTH_PASSWORD_MISMATCH", "비밀번호가 일치하지 않습니다."), REFRESH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "유효하지 않은 refresh token입니다."), REFRESH_TOKEN_EXPIRED(
            HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_EXPIRED",
            "만료되거나 이미 사용된 refresh token입니다."), EMAIL_ALREADY_EXISTS(HttpStatus.FORBIDDEN, "AUTH_EMAIL_ALREADY_EXISTS",
            "이미 존재하는 아이디 입니다."), EMAIL_UNVERIFIED(HttpStatus.FORBIDDEN, "AUTH_EMAIL_UNVERIFIED",
            "인증되지 않은 메일입니다."), MASTER_TOKEN_ENVIRONMENT_FORBIDDEN(HttpStatus.FORBIDDEN,
            "AUTH_MASTER_TOKEN_ENVIRONMENT_FORBIDDEN", "배포환경에서 사용 할 수 없습니다"), MASTER_TOKEN_ACCESS_DENIED(
            HttpStatus.FORBIDDEN, "AUTH_MASTER_TOKEN_ACCESS_DENIED", "허용되지 않은 접근입니다."), CURRENT_PASSWORD_REQUIRED(
            HttpStatus.UNAUTHORIZED, "AUTH_CURRENT_PASSWORD_REQUIRED", "현재 비밀번호를 입력해주세요."), CURRENT_PASSWORD_MISMATCH(
            HttpStatus.UNAUTHORIZED, "AUTH_CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."), DEMO_UNAVAILABLE(
            HttpStatus.NOT_FOUND, "AUTH_DEMO_UNAVAILABLE",
            "Cannot GET /auth/demo/enter"), DEMO_WORKSPACE_NOT_CONFIGURED(HttpStatus.NOT_FOUND,
            "AUTH_DEMO_WORKSPACE_NOT_CONFIGURED", "데모 워크스페이스가 설정되지 않았습니다."), PROVIDER_ALREADY_LINKED(
            HttpStatus.CONFLICT, "AUTH_PROVIDER_ALREADY_LINKED", "이미 연동된 제공자입니다."), OAUTH_ACCOUNT_NOT_LINKED(
            HttpStatus.NOT_FOUND, "AUTH_OAUTH_ACCOUNT_NOT_LINKED", "NOT_LINKED"), LAST_AUTH_METHOD(HttpStatus.CONFLICT,
            "AUTH_LAST_AUTH_METHOD", "LAST_AUTH_METHOD");

    private final HttpStatus status;
    private final String code;
    private final String reason;
}
