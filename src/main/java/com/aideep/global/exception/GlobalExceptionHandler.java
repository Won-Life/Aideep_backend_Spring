package com.aideep.global.exception;

import com.aideep.global.response.ResponseHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 NestJS와 동일한 FAIL 응답 포맷으로 변환한다.
 * <p>
 * ResponseEntityExceptionHandler를 상속해 Spring MVC가 자체적으로 처리하는 예외(404, 405, 415 등)도 원래 상태 코드를 유지한 채 공통 포맷으로 나가게 한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.debug("비즈니스 오류: code={}, status={}, reason={}",
                errorCode.getCode(), errorCode.getStatus().value(), errorCode.getReason());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ResponseHandler.fail(errorCode, e.getData()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        log.debug("접근 권한이 없습니다.");
        return ResponseEntity.status(GlobalErrorCode.FORBIDDEN.getStatus())
                .body(ResponseHandler.fail(GlobalErrorCode.FORBIDDEN, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(GlobalErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ResponseHandler.fail(GlobalErrorCode.INTERNAL_SERVER_ERROR, null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fieldError -> fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));

        ResponseHandler<Object> body = ResponseHandler.fail(GlobalErrorCode.VALIDATION_FAILED, fieldErrors);
        return handleExceptionInternal(ex, body, headers, GlobalErrorCode.VALIDATION_FAILED.getStatus(), request);
    }

    /**
     * ResponseEntityExceptionHandler가 처리하는 나머지 Spring MVC 예외의 본문을 공통 포맷으로 바꾼다. 이미 ResponseHandler인 본문(위 핸들러들이 만든 것)은
     * 그대로 통과시킨다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        log.debug("Spring MVC 오류: type={}, status={}",
                ex.getClass().getSimpleName(), statusCode.value());
        Object responseBody = (body instanceof ResponseHandler<?>)
                ? body
                : ResponseHandler.fail("COMMON" + statusCode.value(), ex.getMessage(), null);
        return super.handleExceptionInternal(ex, responseBody, headers, statusCode, request);
    }
}
