package com.aideep.domain.meeting.controller;

import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.service.MeetingService;
import com.aideep.domain.workspace.entity.WorkspacePermission;
import com.aideep.domain.workspace.service.WorkspacePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Meeting 컨트롤러")
@RequestMapping("/v1/aideep/api/meeting")
public class MeetingController {

    private final MeetingService meetingService;
    private final WorkspacePermissionService workspacePermissionService;

    public MeetingController(MeetingService meetingService, WorkspacePermissionService workspacePermissionService) {
        this.meetingService = meetingService;
        this.workspacePermissionService = workspacePermissionService;
    }

    @PostMapping("/bot")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회의에 녹음·전사 봇 초대")
    public InviteBotResponse inviteBot(@AuthenticationPrincipal UserDetail userDetail,
                                       @Valid @RequestBody InviteBotRequest inviteBotRequest) {
        workspacePermissionService.requirePermission(
                userDetail.userId(), inviteBotRequest.workspaceId(), WorkspacePermission.EDIT);
        return meetingService.inviteBot(inviteBotRequest);
    }
}
