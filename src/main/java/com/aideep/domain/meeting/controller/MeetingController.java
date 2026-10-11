package com.aideep.domain.meeting.controller;

import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.ActiveMeetingResponse;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.service.MeetingQueryService;
import com.aideep.domain.meeting.service.MeetingService;
import com.aideep.domain.workspace.entity.WorkspacePermission;
import com.aideep.domain.workspace.service.WorkspacePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Meeting 컨트롤러")
@RequestMapping("/v1/aideep/api/meeting")
public class MeetingController {

    private final MeetingService meetingService;
    private final MeetingQueryService meetingQueryService;
    private final WorkspacePermissionService workspacePermissionService;

    public MeetingController(MeetingService meetingService, MeetingQueryService meetingQueryService,
                             WorkspacePermissionService workspacePermissionService) {
        this.meetingService = meetingService;
        this.meetingQueryService = meetingQueryService;
        this.workspacePermissionService = workspacePermissionService;
    }

    @PostMapping("/bot")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회의에 녹음·전사 봇 초대")
    public InviteBotResponse inviteBot(@AuthenticationPrincipal UserDetail userDetail,
                                       @Valid @RequestBody InviteBotRequest inviteBotRequest) {
        workspacePermissionService.requirePermission(
                userDetail.userId(), inviteBotRequest.workspaceId(), WorkspacePermission.EDIT);
        return meetingService.inviteBot(inviteBotRequest, userDetail.userId());
    }

    @GetMapping
    @Operation(summary = "워크스페이스의 진행 중 회의 목록 조회",
            description = "실시간 이벤트를 놓친 클라이언트가 재접속 시 회의 상태를 복구하는 경로다.")
    public List<ActiveMeetingResponse> findActiveMeetings(@AuthenticationPrincipal UserDetail userDetail,
                                                          @RequestParam UUID workspaceId) {
        workspacePermissionService.requirePermission(userDetail.userId(), workspaceId, WorkspacePermission.VIEW);
        return meetingQueryService.findActiveMeetings(workspaceId);
    }
}
