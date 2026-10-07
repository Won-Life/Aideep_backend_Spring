package com.aideep.global.config;

import com.aideep.domain.auth.repository.AuthUserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityResponseTest.TestController.class)
@Import({SecurityConfig.class, SecurityResponseTest.TestController.class})
class SecurityResponseTest {
    @org.springframework.web.bind.annotation.RestController
    static class TestController {
        @org.springframework.web.bind.annotation.GetMapping("/private")
        String privateEndpoint() {
            return "ok";
        }
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.aideep.domain.auth.service.JwtTokenService jwtTokenService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.aideep.domain.auth.service.RedisAuthStore redisAuthStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    AuthUserRepository authUserRepository;
    @Autowired
    MockMvc mockMvc;
    // 웹훅 전용 체인이 추가되어 SecurityFilterChain 빈이 둘이므로, 이 테스트가 검증하는 기본(JWT) 체인을 지정한다.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("filterChain")
    SecurityFilterChain securityFilterChain;
    @Autowired
    WebApplicationContext webApplicationContext;

    @Test
    void redisOutageFailsClosedWithServerError() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "HS256").claim("user_id", "11111111-1111-4111-8111-111111111111").build();
        org.mockito.Mockito.when(jwtTokenService.decode("test-token")).thenReturn(jwt);
        org.mockito.Mockito.doThrow(
                        new org.springframework.data.redis.RedisConnectionFailureException("test Redis unavailable"))
                .when(redisAuthStore).validateToken("test-token", "11111111-1111-4111-8111-111111111111", false);
        mockMvc.perform(get("/private").header("Authorization", "Bearer test-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON500"));
        mockMvc.perform(get("/v1/aideep/api/auth/oauth/links").header("Authorization", "Bearer test-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON500"));
    }

    @Test
    void databaseOutageDuringUserLookupFailsClosedWithServerError() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "HS256").claim("user_id", "11111111-1111-4111-8111-111111111111").build();
        org.mockito.Mockito.when(jwtTokenService.decode("test-token")).thenReturn(jwt);
        org.mockito.Mockito.when(authUserRepository.findByIdAndDeletedAtIsNull(
                        java.util.UUID.fromString("11111111-1111-4111-8111-111111111111")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test database unavailable"));

        mockMvc.perform(get("/private").header("Authorization", "Bearer test-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON500"));
    }

    @Test
    void revokedTokenUsesCommonUnauthorizedResponse() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "HS256").claim("user_id", "11111111-1111-4111-8111-111111111111").build();
        org.mockito.Mockito.when(jwtTokenService.decode("test-token")).thenReturn(jwt);
        org.mockito.Mockito.doThrow(new com.aideep.global.exception.BusinessException(
                        com.aideep.domain.auth.exception.AuthError.TOKEN_REVOKED))
                .when(redisAuthStore).validateToken("test-token", "11111111-1111-4111-8111-111111111111", false);
        mockMvc.perform(get("/v1/aideep/api/auth/oauth/links").header("Authorization", "Bearer test-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.reason").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON401"));
    }

    @Test
    void unauthenticatedRequestUsesCommonError() throws Exception {
        mockMvc.perform(get("/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON401"))
                .andExpect(jsonPath("$.error.reason").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.error.data").value(nullValue()))
                .andExpect(jsonPath("$.success").value(nullValue()));
    }

    @Test
    void authenticatedAccessDeniedUsesCommonError() throws Exception {
        // 현재 운영 규칙에는 역할 제한이 없으므로 인가 필터 자리에서 거절을 재현한다.
        Filter deny = (request, response, chain) -> {
            throw new AccessDeniedException("internal authorization detail");
        };
        Filter[] filters = securityFilterChain.getFilters().stream()
                .map(filter -> filter instanceof AuthorizationFilter ? deny : filter)
                .toArray(Filter[]::new);
        MockMvc deniedMockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters).build();

        deniedMockMvc.perform(get("/private").with(user("tester")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON403"))
                .andExpect(jsonPath("$.error.reason").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.error.data").value(nullValue()))
                .andExpect(jsonPath("$.success").value(nullValue()));
    }

    @Test
    void broadApiPrefixIsNoLongerPublic() throws Exception {
        mockMvc.perform(get("/api/missing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON401"));
    }

    /**
     * 웹훅은 사용자 JWT가 아니라 본문 서명으로 인증하므로 기본 체인의 401에 걸리지 않아야 한다. 이 테스트에는 웹훅 컨트롤러가 없으므로 통과한 요청은 404가 된다.
     */
    @Test
    void webhookPathBypassesJwtAuthentication() throws Exception {
        mockMvc.perform(get("/v1/aideep/api/webhooks/recall/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON404"));
    }
}
