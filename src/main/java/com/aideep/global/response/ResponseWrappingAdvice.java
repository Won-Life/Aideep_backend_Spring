package com.aideep.global.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 컨트롤러가 반환한 DTO를 공통 성공 응답 포맷으로 자동 래핑한다. 컨트롤러는 순수 DTO만 반환하면 되고, ResponseHandler로 직접 감쌀 필요가 없다.
 */
@RestControllerAdvice
public class ResponseWrappingAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // springdoc이 제공하는 /v3/api-docs 등은 자체 스키마를 그대로 내보내야 Swagger UI가 동작한다.
        if (returnType.getContainingClass().getName().startsWith("org.springdoc")) {
            return false;
        }
        // String 반환 핸들러는 StringHttpMessageConverter가 처리하므로,
        // 객체로 바꿔 넘기면 ClassCastException이 난다. 컨트롤러는 raw String을 반환하지 않는다.
        return !StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // GlobalExceptionHandler가 만든 FAIL 응답이나 직접 감싼 응답은 이중 래핑하지 않는다.
        if (body instanceof ResponseHandler<?>) {
            return body;
        }
        return ResponseHandler.success(body);
    }
}
