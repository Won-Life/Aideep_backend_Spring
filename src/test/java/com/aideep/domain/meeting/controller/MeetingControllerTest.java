package com.aideep.domain.meeting.controller;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.ActiveMeetingResponse;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.service.MeetingQueryService;
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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeetingControllerTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID MEETING_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String VALID_REQUEST = """
            {
              "url": "https://meet.google.com/abc-defg-hij",
              "type": "GOOGLE",
              "workspaceId": "22222222-2222-4222-8222-222222222222",
              "nodeId": "44444444-4444-4444-8444-444444444444"
            }
            """;

    private MeetingService meetingService;
    private MeetingQueryService meetingQueryService;
    private WorkspacePermissionService workspacePermissionService;
    private UserDetail userDetail;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        meetingService = mock(MeetingService.class);
        meetingQueryService = mock(MeetingQueryService.class);
        workspacePermissionService = mock(WorkspacePermissionService.class);
        AuthUser authUser = new AuthUser("user@example.com", "password", Instant.EPOCH);
        userDetail = UserDetail.from(authUser, false);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MeetingController(meetingService, meetingQueryService, workspacePermissionService))
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void checksWorkspacePermissionAndReturnsCreatedBotId() throws Exception {
        when(meetingService.inviteBot(any(InviteBotRequest.class), eq(userDetail.userId())))
                .thenReturn(new InviteBotResponse(BOT_ID));

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.success.botId").value(BOT_ID.toString()));

        verify(workspacePermissionService).requirePermission(
                userDetail.userId(), WORKSPACE_ID, WorkspacePermission.EDIT);
        verify(meetingService).inviteBot(any(InviteBotRequest.class), eq(userDetail.userId()));
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

    @Test
    void surfacesMissingMeetingNodeAsNotFound() throws Exception {
        when(meetingService.inviteBot(any(InviteBotRequest.class), eq(userDetail.userId())))
                .thenThrow(new com.aideep.global.exception.BusinessException(
                        com.aideep.domain.meeting.exception.MeetingError.NODE_NOT_FOUND));

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("MEETING-006"));
    }

    @Test
    void surfacesMissingWorkspaceAsNotFound() throws Exception {
        when(meetingService.inviteBot(any(InviteBotRequest.class), eq(userDetail.userId())))
                .thenThrow(new com.aideep.global.exception.BusinessException(
                        com.aideep.domain.meeting.exception.MeetingError.WORKSPACE_NOT_FOUND));

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.errorCode").value("MEETING-005"));
    }

    /**
     * 권한 검사는 컨트롤러가 먼저 하므로, 멤버십이 없는 워크스페이스는 존재 여부를 흘리지 않고 403으로 끝난다.
     */
    @Test
    void deniesWorkspaceWithoutMembershipBeforeCallingTheService() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(workspacePermissionService)
                .requirePermission(userDetail.userId(), WORKSPACE_ID, WorkspacePermission.EDIT);

        mockMvc.perform(post("/v1/aideep/api/meeting/bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON403"));

        verifyNoInteractions(meetingService);
    }

    @Test
    void returnsActiveMeetingsForWorkspaceViewers() throws Exception {
        when(meetingQueryService.findActiveMeetings(WORKSPACE_ID)).thenReturn(List.of(
                new ActiveMeetingResponse(MEETING_ID, NODE_ID, BOT_ID, "RECORDING", null,
                        Instant.parse("2026-10-06T01:10:00Z"), Instant.parse("2026-10-06T01:00:00Z"))));

        mockMvc.perform(get("/v1/aideep/api/meeting").param("workspaceId", WORKSPACE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success[0].meetingId").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.success[0].nodeId").value(NODE_ID.toString()))
                .andExpect(jsonPath("$.success[0].botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.success[0].status").value("RECORDING"));

        verify(workspacePermissionService).requirePermission(
                userDetail.userId(), WORKSPACE_ID, WorkspacePermission.VIEW);
    }

    @Test
    void deniesActiveMeetingLookupWithoutWorkspaceMembership() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(workspacePermissionService)
                .requirePermission(userDetail.userId(), WORKSPACE_ID, WorkspacePermission.VIEW);

        mockMvc.perform(get("/v1/aideep/api/meeting").param("workspaceId", WORKSPACE_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("COMMON403"));

        verifyNoInteractions(meetingQueryService);
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
