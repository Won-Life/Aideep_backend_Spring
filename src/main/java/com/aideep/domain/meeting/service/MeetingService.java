package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.event.MeetingRealtimeEvent;
import com.aideep.domain.meeting.dto.event.MeetingWorkspaceEvent;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.domain.node.service.NodeQueryService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class MeetingService {

    private final RecallBotClient recallBotClient;
    private final MeetingRepository meetingRepository;
    private final WorkspaceQueryService workspaceQueryService;
    private final NodeQueryService nodeQueryService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    public MeetingService(RecallBotClient recallBotClient, MeetingRepository meetingRepository,
                          WorkspaceQueryService workspaceQueryService, NodeQueryService nodeQueryService,
                          ApplicationEventPublisher applicationEventPublisher, Clock clock) {
        this.recallBotClient = recallBotClient;
        this.meetingRepository = meetingRepository;
        this.workspaceQueryService = workspaceQueryService;
        this.nodeQueryService = nodeQueryService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
    }

    /**
     * 회의를 먼저 저장한 뒤 봇을 생성하고, 돌려받은 botId를 같은 트랜잭션에서 연결한다.
     * <p>
     * 회의를 먼저 저장하는 이유는 봇 {@code metadata}에 넣을 meetingId가 필요하고, 상태 웹훅이 회의 행보다 먼저 도착하는 상황을 만들지 않기
     * 위함이다. 봇 생성이 실패하면 같은 트랜잭션이 롤백되어 REQUESTED 상태의 회의가 남지 않는다. botId를 연결하지 않으면 상태 웹훅이 회의를 찾을 수
     * 없으므로 연결은 생략할 수 없다.
     */
    @Transactional
    public InviteBotResponse inviteBot(InviteBotRequest inviteBotRequest, UUID userId) {
        requireActiveWorkspaceAndNode(inviteBotRequest.workspaceId(), inviteBotRequest.nodeId());
        requireNoActiveBot(inviteBotRequest.url());

        Instant now = clock.instant();
        // saveAndFlush로 진행 중 URL unique 인덱스를 Recall 호출 전에 확인한다. 커밋까지 미루면 중복 요청으로도 봇이 만들어진다.
        Meeting meeting = saveNewMeeting(inviteBotRequest, userId, now);

        InviteBotResponse inviteBotResponse = recallBotClient.invite(inviteBotRequest, meeting.getId());

        meeting.linkBot(inviteBotResponse.botId(), clock.instant());
        meetingRepository.save(meeting);
        // 커밋 후에만 발행한다. linkBot 이후여야 payload의 botId가 비지 않는다.
        applicationEventPublisher.publishEvent(
                MeetingRealtimeEvent.of(MeetingWorkspaceEvent.botRequested(meeting, meeting.getCreatedAt())));
        return inviteBotResponse;
    }

    private Meeting saveNewMeeting(InviteBotRequest inviteBotRequest, UUID userId, Instant now) {
        try {
            return meetingRepository.saveAndFlush(Meeting.request(inviteBotRequest.workspaceId(),
                    inviteBotRequest.nodeId(), userId, inviteBotRequest.url(), inviteBotRequest.type(), now));
        } catch (DataIntegrityViolationException exception) {
            // 사전 조회를 통과한 동시 요청끼리의 경합이다. 인덱스가 최종 방어선이다.
            throw new BusinessException(MeetingError.BOT_ALREADY_INVITED,
                    "Another bot is already active for this meeting url.");
        }
    }

    /**
     * 같은 회의 링크에 봇이 두 대 들어가면 녹음과 전사가 중복된다. 종료된 회의는 제외하므로 같은 링크로 다시 초대할 수 있다.
     */
    private void requireNoActiveBot(String meetingUrl) {
        if (meetingRepository.existsByMeetingUrlAndStatusInAndDeletedAtIsNull(meetingUrl, MeetingStatus.active())) {
            throw new BusinessException(MeetingError.BOT_ALREADY_INVITED,
                    "A bot is already active for this meeting url.");
        }
    }

    /**
     * 검증 없이 저장하면 외래 키 위반이 500으로 나가고, 다른 워크스페이스의 노드는 제약 조건을 통과해 그대로 저장된다. 두 경우를 모두 비즈니스 오류로
     * 바꾼다. 다른 도메인의 저장소를 직접 쓰지 않고 각 도메인의 조회 서비스를 경계로 사용한다.
     */
    private void requireActiveWorkspaceAndNode(UUID workspaceId, UUID nodeId) {
        if (!workspaceQueryService.existsActiveWorkspace(workspaceId)) {
            throw new BusinessException(MeetingError.WORKSPACE_NOT_FOUND,
                    "Workspace not found. workspaceId=" + workspaceId);
        }
        if (!nodeQueryService.existsActiveNode(workspaceId, nodeId)) {
            throw new BusinessException(MeetingError.NODE_NOT_FOUND,
                    "Node not found in workspace. nodeId=" + nodeId + " workspaceId=" + workspaceId);
        }
    }
}
