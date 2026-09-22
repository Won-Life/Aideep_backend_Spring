package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Bottype;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class MeetingServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void invitesRecallBot() {
        RecallBotClient recallBotClient = org.mockito.Mockito.mock(RecallBotClient.class);
        MeetingService meetingService = new MeetingService(recallBotClient);
        InviteBotRequest inviteBotRequest = new InviteBotRequest(
                "https://meet.google.com/abc-defg-hij", Bottype.GOOGLE, WORKSPACE_ID);
        InviteBotResponse expected = new InviteBotResponse(BOT_ID);
        when(recallBotClient.invite(inviteBotRequest)).thenReturn(expected);

        InviteBotResponse actual = meetingService.inviteBot(inviteBotRequest);

        assertThat(actual).isEqualTo(expected);
        verify(recallBotClient).invite(inviteBotRequest);
    }
}
