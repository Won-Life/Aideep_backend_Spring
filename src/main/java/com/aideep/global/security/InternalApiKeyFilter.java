package com.aideep.global.security;

import com.aideep.global.config.InternalApiProperties;
import com.aideep.global.exception.GlobalErrorCode;
import com.aideep.global.response.ResponseHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 내부 서버 간 API를 사용자 JWT가 아니라 공유 시크릿 헤더로 인증한다.
 */
@Slf4j
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "X-Internal-Key";

    private final InternalApiProperties internalApiProperties;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(InternalApiProperties internalApiProperties, ObjectMapper objectMapper) {
        this.internalApiProperties = internalApiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matches(request.getHeader(KEY_HEADER))) {
            log.debug("내부 API 키가 올바르지 않습니다. path={}", request.getRequestURI());
            response.setStatus(GlobalErrorCode.UNAUTHORIZED.getStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ResponseHandler.fail(GlobalErrorCode.UNAUTHORIZED, null));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String presentedKey) {
        if (presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(presentedKey.getBytes(StandardCharsets.UTF_8),
                internalApiProperties.key().getBytes(StandardCharsets.UTF_8));
    }
}
