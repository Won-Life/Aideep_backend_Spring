package com.aideep.global.config;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.controller.AuthController;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.service.AuthService;
import com.aideep.domain.auth.service.JwtTokenService;
import com.aideep.domain.auth.service.OAuthService;
import com.aideep.domain.auth.service.RedisAuthStore;
import com.aideep.domain.auth.service.VerificationMailService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SwaggerConfig.class, SecurityConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class})
class SwaggerPathsTest {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    OAuthService oAuthService;
    @MockitoBean
    RedisAuthStore redisAuthStore;
    @MockitoBean
    VerificationMailService verificationMailService;
    @MockitoBean
    AuthProperties authProperties;
    @MockitoBean
    JwtTokenService jwtTokenService;
    @MockitoBean
    AuthUserRepository authUserRepository;

    @Test
    void documentsShortPathsAndKeepsOriginalRequestUrls() throws Exception {
        // 캐시된 문서를 다시 조회해도 접두어가 중복되거나 서버 주소가 사라지지 않는다.
        for (int i = 0; i < 2; i++) {
            String document = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/user/user']").doesNotExist())
                    .andExpect(jsonPath("$.paths['/auth/login'].post.responses['201']").exists())
                    .andExpect(jsonPath("$.paths['/auth/oauth/link/{provider}'].delete").exists())
                    .andExpect(jsonPath("$.paths['/v1/api/aideep/user/user']").doesNotExist())
                    .andExpect(jsonPath("$.paths['/v1/aideep/api/auth/login']").doesNotExist())
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                    .andReturn().getResponse().getContentAsString();
            var jsonNode = objectMapper.readTree(document);
            String authServerUrl = jsonNode.get("paths").get("/auth/login").at("/servers/0/url").asString();
            assertThat(authServerUrl + "/auth/login").isEqualTo("http://localhost/v1/aideep/api/auth/login");
        }

        when(authService.login(new LoginRequest("user@example.com", "password")))
                .thenReturn(new TokensResponse("access", "refresh"));
        mockMvc.perform(post("/v1/aideep/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success.accessToken").value("access"));
    }
}
