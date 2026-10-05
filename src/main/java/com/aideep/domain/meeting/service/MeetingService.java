package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.repository.MeetingRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MeetingService {

    private final RecallBotClient recallBotClient;
    private final MeetingRepository meetingRepository;

    public MeetingService(RecallBotClient recallBotClient, MeetingRepository meetingRepository) {
        this.recallBotClient = recallBotClient;
        this.meetingRepository = meetingRepository;
    }

    public InviteBotResponse inviteBot(InviteBotRequest inviteBotRequest, UUID userId) {
        meetingRepository.save(Meeting.request(inviteBotRequest.workspaceId(), inviteBotRequest.nodeId(), userId,
                inviteBotRequest.url(), inviteBotRequest.type(), Instant.now()));
        return recallBotClient.invite(inviteBotRequest);
    }
}
