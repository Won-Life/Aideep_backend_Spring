package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.response.ActiveMeetingResponse;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 실시간 이벤트를 놓친 클라이언트의 복구 경로. Pub/Sub은 구독자가 없으면 메시지를 버리므로 실시간 계약은 이 조회와 함께 성립한다.
 */
@Service
@Transactional(readOnly = true)
public class MeetingQueryService {

    private final MeetingRepository meetingRepository;

    public MeetingQueryService(MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    /**
     * 진행 중 회의만 반환한다. 판정 집합은 {@link MeetingStatus#active()}를 그대로 써서 진행 중 URL 부분 유니크 인덱스 조건과
     * 어긋나지 않게 한다.
     */
    public List<ActiveMeetingResponse> findActiveMeetings(UUID workspaceId) {
        return meetingRepository
                .findByWorkspaceIdAndStatusInAndDeletedAtIsNull(workspaceId, MeetingStatus.active())
                .stream()
                .map(ActiveMeetingResponse::from)
                .toList();
    }
}
