package com.aideep.domain.meeting.controller;

import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.ActiveMeetingResponse;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.dto.response.MeetingUsageResponse;
import com.aideep.domain.meeting.service.MeetingAdminService;
import com.aideep.domain.meeting.service.MeetingQueryService;
import com.aideep.domain.meeting.service.MeetingService;
import com.aideep.domain.meeting.service.MeetingUsageService;
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
    private final MeetingUsageService meetingUsageService;
    private final MeetingAdminService meetingAdminService;
    private final WorkspacePermissionService workspacePermissionService;

    public MeetingController(MeetingService meetingService, MeetingQueryService meetingQueryService,
                             MeetingUsageService meetingUsageService, MeetingAdminService meetingAdminService,
                             WorkspacePermissionService workspacePermissionService) {
        this.meetingService = meetingService;
        this.meetingQueryService = meetingQueryService;
        this.meetingUsageService = meetingUsageService;
        this.meetingAdminService = meetingAdminService;
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

    /**
     * 내부망에서만 여는 사용량 대시보드(`/usage-dashboard.html`)가 읽는 집계. 워크스페이스 단위가 아니라 전체 사용량이므로 사용자 권한 검사가 없고,
     * 그래서 외부에 노출되면 안 된다.
     */
    @GetMapping("/usage")
    @Operation(summary = "시간별 회의 봇 사용량 집계(내부망 대시보드용)")
    public MeetingUsageResponse findHourlyUsage(@RequestParam(defaultValue = "24") int hours) {
        return meetingUsageService.findHourlyUsage(hours);
    }

    /**
     * 사용량 대시보드에서 잘못 남은 봇을 정리하는 경로다. 사용량 조회와 같은 이유로 사용자 권한 검사가 없으므로 내부망에서만 열려야 한다.
     */
    @PostMapping("/{meetingId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "회의에서 봇 강제 내보내기(내부망 대시보드용)",
            description = "Recall에만 지시하고 회의 상태는 바꾸지 않는다. 상태는 Recall이 보내는 종료 웹훅이 반영한다.")
    public void forceLeave(@PathVariable UUID meetingId) {
        meetingAdminService.forceLeave(meetingId);
    }

    @PostMapping("/{meetingId}/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Recall에서 회의 상태 다시 읽기(내부망 대시보드용)",
            description = "웹훅을 놓친 회의를 Recall의 마지막 봇 상태로 맞춘다.")
    public void syncStatus(@PathVariable UUID meetingId) {
        meetingAdminService.syncStatus(meetingId);
    }

    @PostMapping("/{meetingId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "봇이 없는 회의 기록만 종료(내부망 대시보드용)",
            description = "botId가 없어 상태 웹훅이 영원히 오지 않는 행을 닫는다. 봇이 연결된 회의에는 쓸 수 없다.")
    public void closeRecord(@PathVariable UUID meetingId) {
        meetingAdminService.closeRecord(meetingId);
    }
}
