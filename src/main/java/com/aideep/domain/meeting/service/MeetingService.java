package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import org.springframework.stereotype.Service;

@Service
public class MeetingService {

    private final RecallBotClient recallBotClient;

    public MeetingService(RecallBotClient recallBotClient) {
        this.recallBotClient = recallBotClient;
    }

    public InviteBotResponse inviteBot(InviteBotRequest inviteBotRequest) {
        return recallBotClient.invite(inviteBotRequest);
    }
}
