package com.aideep.domain.auth;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.auth.controller.AuthController;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.service.AuthService;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthResponseTest {
    private static final String LOGIN = "/v1/aideep/api/auth/login";
    private static final String VALID_LOGIN = "{\"email\":\"user@example.com\",\"password\":\"password\"}";
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, null, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void authBusinessErrorsUseGlobalHandler() throws Exception {
        for (AuthError error : AuthError.values()) {
            doThrow(new BusinessException(error)).when(authService).login(any(LoginRequest.class));
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(VALID_LOGIN))
                    .andExpect(status().is(error.getStatus().value()))
                    .andExpect(jsonPath("$.resultType").value("FAIL"))
                    .andExpect(jsonPath("$.error.errorCode").value(error.getCode()))
                    .andExpect(jsonPath("$.error.reason").value(error.getReason()))
                    .andExpect(jsonPath("$.error.data").value(nullValue()))
                    .andExpect(jsonPath("$.success").value(nullValue()));
        }
    }

    @Test
    void validationUsesFieldErrors() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errorCode").value("VALID400"))
                .andExpect(jsonPath("$.error.data.email").isString())
                .andExpect(jsonPath("$.error.data.password").isString());
        verifyNoInteractions(authService);
    }

    @Test
    void malformedJsonUsesCommonError() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON400"));
        verifyNoInteractions(authService);
    }

    @Test
    void accessDeniedUsesCommonError() throws Exception {
        doThrow(new AccessDeniedException("internal detail")).when(authService).login(any(LoginRequest.class));
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(VALID_LOGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON403"))
                .andExpect(jsonPath("$.error.reason").value("접근 권한이 없습니다."));
    }

    @Test
    void unexpectedErrorsUseCommonErrorWithoutInternalDetails() throws Exception {
        doThrow(new IllegalStateException("internal detail")).when(authService).login(any(LoginRequest.class));
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(VALID_LOGIN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON500"))
                .andExpect(jsonPath("$.error.reason").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.error.data").value(nullValue()));
    }

    @Test
    void splitDtosKeepSuccessContract() throws Exception {
        when(authService.login(new LoginRequest("user@example.com", "password")))
                .thenReturn(new TokensResponse("access", "refresh"));
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(VALID_LOGIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.success.accessToken").value("access"))
                .andExpect(jsonPath("$.success.refreshToken").value("refresh"));
    }
}
