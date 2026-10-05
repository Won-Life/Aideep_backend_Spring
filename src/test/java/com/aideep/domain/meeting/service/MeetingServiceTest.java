package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

class MeetingServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String MEETING_URL = "https://meet.google.com/abc-defg-hij";

    @Test
    void invitesRecallBot() {
        RecallBotClient recallBotClient = mock(RecallBotClient.class);
        MeetingRepository meetingRepository = mock(MeetingRepository.class);
        MeetingService meetingService = new MeetingService(recallBotClient, meetingRepository);
        InviteBotRequest inviteBotRequest = new InviteBotRequest(
                MEETING_URL, Bottype.GOOGLE, WORKSPACE_ID, NODE_ID);
        InviteBotResponse expected = new InviteBotResponse(BOT_ID);
        when(recallBotClient.invite(inviteBotRequest)).thenReturn(expected);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InviteBotResponse actual = meetingService.inviteBot(inviteBotRequest, USER_ID);

        assertThat(actual).isEqualTo(expected);
        verify(recallBotClient).invite(inviteBotRequest);
    }

    @Test
    void savesRequestedMeetingWithRequestedMeetingUrl() {
        RecallBotClient recallBotClient = mock(RecallBotClient.class);
        MeetingRepository meetingRepository = mock(MeetingRepository.class);
        MeetingService meetingService = new MeetingService(recallBotClient, meetingRepository);
        InviteBotRequest inviteBotRequest = new InviteBotRequest(
                MEETING_URL, Bottype.GOOGLE, WORKSPACE_ID, NODE_ID);
        when(recallBotClient.invite(inviteBotRequest)).thenReturn(new InviteBotResponse(BOT_ID));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        meetingService.inviteBot(inviteBotRequest, USER_ID);

        ArgumentCaptor<Meeting> captor = ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository).save(captor.capture());
        Meeting saved = captor.getValue();
        assertThat(saved.getMeetingUrl()).isEqualTo(MEETING_URL);
        assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(saved.getNodeId()).isEqualTo(NODE_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getBotType()).isEqualTo(Bottype.GOOGLE);
        assertThat(saved.getStatus()).isEqualTo(MeetingStatus.REQUESTED);
    }
}
