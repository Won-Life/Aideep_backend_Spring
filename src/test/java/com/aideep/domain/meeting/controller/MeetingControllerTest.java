package com.aideep.domain.meeting.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.service.MeetingService;
import com.aideep.domain.workspace.entity.WorkspacePermission;
import com.aideep.domain.workspace.service.WorkspacePermissionService;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.UUID;

class MeetingControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String VALID_REQUEST = """
            {
              "url": "https://meet.google.com/abc-defg-hij",
              "type": "GOOGLE",
              "workspaceId": "22222222-2222-4222-8222-222222222222"
            }
            """;

    private MeetingService meetingService;
    private WorkspacePermissionService workspacePermissionService;
    private UserDetail userDetail;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        meetingService = mock(MeetingService.class);
        workspacePermissionService = mock(WorkspacePermissionService.class);
        AuthUser authUser = new AuthUser("user@example.com", "user", "password", Instant.EPOCH);
        userDetail = UserDetail.from(authUser, false);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MeetingController(meetingService, workspacePermissionService))
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void checksWorkspacePermissionAndReturnsCreatedBotId() throws Exception {
        when(meetingService.inviteBot(any(InviteBotRequest.class))).thenReturn(new InviteBotResponse(BOT_ID));

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.success.botId").value(BOT_ID.toString()));

        verify(workspacePermissionService).requirePermission(
                userDetail.userId(), WORKSPACE_ID, WorkspacePermission.EDIT);
        verify(meetingService).inviteBot(any(InviteBotRequest.class));
    }

    @Test
    void rejectsNonHttpsMeetingUrl() throws Exception {
        String request = VALID_REQUEST.replace("https://", "http://");

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errorCode").value("VALID400"))
                .andExpect(jsonPath("$.error.data.url").isString());

        verifyNoInteractions(workspacePermissionService, meetingService);
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer modelAndViewContainer,
                                          NativeWebRequest nativeWebRequest,
                                          WebDataBinderFactory webDataBinderFactory) {
                return userDetail;
            }
        };
    }
}
