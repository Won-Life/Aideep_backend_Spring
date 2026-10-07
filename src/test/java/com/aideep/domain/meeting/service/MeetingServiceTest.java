package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import org.springframework.dao.DataIntegrityViolationException;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.domain.node.service.NodeQueryService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class MeetingServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_WORKSPACE_NODE_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String MEETING_URL = "https://meet.google.com/abc-defg-hij";
    private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");

    private RecallBotClient recallBotClient;
    private MeetingRepository meetingRepository;
    private WorkspaceQueryService workspaceQueryService;
    private NodeQueryService nodeQueryService;
    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        recallBotClient = mock(RecallBotClient.class);
        meetingRepository = mock(MeetingRepository.class);
        workspaceQueryService = mock(WorkspaceQueryService.class);
        nodeQueryService = mock(NodeQueryService.class);
        meetingService = new MeetingService(recallBotClient, meetingRepository, workspaceQueryService,
                nodeQueryService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceQueryService.existsActiveWorkspace(WORKSPACE_ID)).thenReturn(true);
        when(nodeQueryService.existsActiveNode(WORKSPACE_ID, NODE_ID)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingRepository.saveAndFlush(any(Meeting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(meetingRepository.existsByMeetingUrlAndStatusInAndDeletedAtIsNull(MEETING_URL, MeetingStatus.active()))
                .thenReturn(false);
    }

    @Test
    void invitesRecallBotWithTheSavedMeetingId() {
        when(recallBotClient.invite(eq(request()), any(UUID.class))).thenReturn(new InviteBotResponse(BOT_ID));

        InviteBotResponse actual = meetingService.inviteBot(request(), USER_ID);

        assertThat(actual.botId()).isEqualTo(BOT_ID);
        ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository, org.mockito.Mockito.atLeastOnce()).save(meetingCaptor.capture());
        UUID meetingId = meetingCaptor.getValue().getId();
        verify(recallBotClient).invite(request(), meetingId);
    }

    @Test
    void savesRequestedMeetingWithTheInjectedClock() {
        when(recallBotClient.invite(eq(request()), any(UUID.class))).thenReturn(new InviteBotResponse(BOT_ID));

        meetingService.inviteBot(request(), USER_ID);

        ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository, org.mockito.Mockito.atLeastOnce()).save(meetingCaptor.capture());
        Meeting saved = meetingCaptor.getValue();
        assertThat(saved.getMeetingUrl()).isEqualTo(MEETING_URL);
        assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(saved.getNodeId()).isEqualTo(NODE_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getBotType()).isEqualTo(Bottype.GOOGLE);
        assertThat(saved.getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    /**
     * botId를 저장하지 않으면 상태 웹훅이 bot_id로 회의를 찾을 수 없어 어떤 상태 변화도 반영되지 않는다.
     */
    @Test
    void linksReturnedBotIdToTheMeeting() {
        when(recallBotClient.invite(eq(request()), any(UUID.class))).thenReturn(new InviteBotResponse(BOT_ID));

        meetingService.inviteBot(request(), USER_ID);

        ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository, org.mockito.Mockito.atLeastOnce()).save(meetingCaptor.capture());
        assertThat(meetingCaptor.getAllValues()).allSatisfy(meeting -> assertThat(meeting.getId()).isNotNull());
        assertThat(meetingCaptor.getValue().getBotId()).isEqualTo(BOT_ID);
    }

    @Test
    void doesNotLinkBotWhenRecallRejectsTheInvitation() {
        when(recallBotClient.invite(eq(request()), any(UUID.class)))
                .thenThrow(new BusinessException(MeetingError.BOT_INVITATION_REJECTED));

        assertThatThrownBy(() -> meetingService.inviteBot(request(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.BOT_INVITATION_REJECTED);

        ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository).saveAndFlush(meetingCaptor.capture());
        verify(meetingRepository, org.mockito.Mockito.never()).save(any(Meeting.class));
        assertThat(meetingCaptor.getValue().getBotId()).isNull();
    }

    /**
     * 검증이 없으면 외래 키 위반이 500으로 나간다.
     */
    @Test
    void rejectsMissingWorkspaceBeforeTouchingRecall() {
        when(workspaceQueryService.existsActiveWorkspace(WORKSPACE_ID)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.inviteBot(request(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.WORKSPACE_NOT_FOUND);

        verifyNoInteractions(recallBotClient, meetingRepository);
        verifyNoInteractions(nodeQueryService);
    }

    @Test
    void rejectsMissingNodeBeforeTouchingRecall() {
        when(nodeQueryService.existsActiveNode(WORKSPACE_ID, NODE_ID)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.inviteBot(request(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.NODE_NOT_FOUND);

        verifyNoInteractions(recallBotClient, meetingRepository);
    }

    /**
     * 다른 워크스페이스의 노드는 외래 키를 통과하므로 검증이 없으면 그대로 저장된다.
     */
    @Test
    void rejectsNodeThatBelongsToAnotherWorkspace() {
        when(nodeQueryService.existsActiveNode(WORKSPACE_ID, OTHER_WORKSPACE_NODE_ID)).thenReturn(false);
        InviteBotRequest foreignNode = new InviteBotRequest(
                MEETING_URL, Bottype.GOOGLE, WORKSPACE_ID, OTHER_WORKSPACE_NODE_ID);

        assertThatThrownBy(() -> meetingService.inviteBot(foreignNode, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.NODE_NOT_FOUND);

        verifyNoInteractions(recallBotClient, meetingRepository);
    }

    /**
     * 같은 링크에 봇이 두 대 들어가면 녹음과 전사가 중복된다.
     */
    @Test
    void rejectsSecondBotForTheSameMeetingUrl() {
        when(meetingRepository.existsByMeetingUrlAndStatusInAndDeletedAtIsNull(MEETING_URL, MeetingStatus.active()))
                .thenReturn(true);

        assertThatThrownBy(() -> meetingService.inviteBot(request(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.BOT_ALREADY_INVITED);

        verifyNoInteractions(recallBotClient);
        verify(meetingRepository, org.mockito.Mockito.never()).saveAndFlush(any(Meeting.class));
    }

    /**
     * 종료된 회의는 진행 중 집합에서 빠지므로 같은 링크로 다시 초대할 수 있다.
     */
    @Test
    void checksOnlyActiveStatusesForTheDuplicateGuard() {
        when(recallBotClient.invite(eq(request()), any(UUID.class))).thenReturn(new InviteBotResponse(BOT_ID));

        meetingService.inviteBot(request(), USER_ID);

        verify(meetingRepository).existsByMeetingUrlAndStatusInAndDeletedAtIsNull(MEETING_URL, MeetingStatus.active());
        assertThat(MeetingStatus.active()).containsExactlyInAnyOrder(MeetingStatus.REQUESTED, MeetingStatus.JOINING,
                MeetingStatus.WAITING_ROOM, MeetingStatus.IN_CALL_NOT_RECORDING, MeetingStatus.RECORDING);
    }

    /**
     * 사전 조회를 통과한 동시 요청은 unique 인덱스에서 걸린다. 그 위반도 같은 비즈니스 오류로 바꾼다.
     */
    @Test
    void convertsUniqueIndexViolationToTheSameConflict() {
        when(meetingRepository.saveAndFlush(any(Meeting.class)))
                .thenThrow(new DataIntegrityViolationException("uq_meetings_active_url"));

        assertThatThrownBy(() -> meetingService.inviteBot(request(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.BOT_ALREADY_INVITED);

        verifyNoInteractions(recallBotClient);
    }

    private InviteBotRequest request() {
        return new InviteBotRequest(MEETING_URL, Bottype.GOOGLE, WORKSPACE_ID, NODE_ID);
    }
}
