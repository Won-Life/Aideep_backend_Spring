package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.global.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 잘못 남은 봇과 회의 행을 운영자가 정리하는 경로. 내부망 사용량 대시보드에서만 호출한다.
 * <p>
 * 두 동작은 책임이 다르다. {@link #forceLeave}는 Recall에만 지시하고 DB는 건드리지 않는다. 상태 전이는 기존 상태 웹훅
 * 경로(단일 진입점)가 처리해야 순서가 뒤바뀐 이벤트에 대한 보호가 그대로 유지되기 때문이다. {@link #closeRecord}는 반대로 Recall에
 * 지시할 대상(botId)이 없어 웹훅이 영원히 오지 않는 행만 우리 쪽에서 닫는다.
 */
@Service
public class MeetingAdminService {

    /** 운영자가 닫은 행임을 남기는 표식. 새 상태 enum을 만들지 않으려고 기존 종료 상태의 sub code를 쓴다. */
    static final String FORCED_CLOSE_SUB_CODE = "forced_close";

    private static final Logger log = LoggerFactory.getLogger(MeetingAdminService.class);

    private final MeetingRepository meetingRepository;
    private final RecallBotClient recallBotClient;
    private final RecallStatusMapper recallStatusMapper;
    private final Clock clock;

    public MeetingAdminService(MeetingRepository meetingRepository, RecallBotClient recallBotClient,
                               RecallStatusMapper recallStatusMapper, Clock clock) {
        this.meetingRepository = meetingRepository;
        this.recallBotClient = recallBotClient;
        this.recallStatusMapper = recallStatusMapper;
        this.clock = clock;
    }

    /**
     * Recall에 봇의 현재 상태를 물어 회의 행을 맞춘다. 웹훅이 유실됐을 때 상태를 되찾는 유일한 경로다.
     * <p>
     * 웹훅과 같은 {@link Meeting#applyStatus} 관문을 쓰므로, 조회 결과가 이미 반영된 이벤트보다 과거면 그대로 무시된다. 아직 Recall에도
     * 상태 변화가 없거나 우리가 모르는 코드면 아무것도 바꾸지 않는다.
     *
     * @return 상태가 실제로 바뀌었으면 {@code true}
     */
    @Transactional
    public boolean syncStatus(UUID meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        if (meeting.getBotId() == null) {
            throw new BusinessException(MeetingError.BOT_NOT_LINKED,
                    "Meeting has no bot to sync. meetingId=" + meetingId);
        }
        return syncFromRecall(meeting);
    }

    /**
     * 봇이 있는데 오래 조용한 회의를 Recall 기준으로 보정한다. 웹훅 전달이 끊긴 구간의 행이 영원히 열린 채 남는 것을 막는다.
     *
     * @return 상태가 바뀐 회의 수
     */
    @Transactional
    public int syncStalled(Duration stallThreshold) {
        Instant staleBefore = clock.instant().minus(stallThreshold);
        List<Meeting> stalled = meetingRepository.findStalled(MeetingStatus.active(), staleBefore);
        int changed = 0;
        for (Meeting meeting : stalled) {
            // 한 회의의 실패가 나머지 보정을 막지 않도록 각각 독립으로 처리한다.
            try {
                if (syncFromRecall(meeting)) {
                    changed++;
                }
            } catch (BusinessException exception) {
                log.warn("회의 상태 보정에 실패했습니다. meetingId={} code={}",
                        meeting.getId(), exception.getErrorCode().getCode());
            }
        }
        if (!stalled.isEmpty()) {
            log.info("멈춘 회의 {}건을 Recall과 맞췄고 그중 {}건의 상태가 바뀌었습니다.", stalled.size(), changed);
        }
        return changed;
    }

    private boolean syncFromRecall(Meeting meeting) {
        RecallBotClient.RecallBotStatus botStatus = recallBotClient.fetchStatus(meeting.getBotId()).orElse(null);
        if (botStatus == null) {
            return false;
        }
        MeetingStatus status = recallStatusMapper.map(botStatus.code()).orElse(null);
        if (status == null) {
            log.info("Recall의 마지막 상태를 매핑하지 않습니다. meetingId={} code={}", meeting.getId(), botStatus.code());
            return false;
        }
        boolean applied = meeting.applyStatus(status, botStatus.subCode(), botStatus.occurredAt(), clock.instant());
        if (applied) {
            meetingRepository.save(meeting);
            log.info("Recall 조회로 회의 상태를 맞췄습니다. meetingId={} status={} subCode={}",
                    meeting.getId(), status, botStatus.subCode());
        }
        return applied;
    }

    /**
     * 봇을 회의에서 강제로 내보낸다. 되돌릴 수 없다.
     * <p>
     * DB는 바꾸지 않는다. Recall이 보내는 종료 웹훅이 회의 행을 닫으므로, 여기서 상태를 미리 쓰면 뒤에 오는 웹훅과 두 경로가 같은 행을 다투게 된다.
     * 웹훅이 끝내 오지 않으면 행은 진행 중으로 남고, 그때는 운영자가 다시 판단할 수 있도록 그대로 둔다.
     */
    @Transactional(readOnly = true)
    public void forceLeave(UUID meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        if (meeting.getStatus().isTerminal()) {
            throw new BusinessException(MeetingError.MEETING_ALREADY_ENDED,
                    "Meeting already ended. meetingId=" + meetingId);
        }
        if (meeting.getBotId() == null) {
            throw new BusinessException(MeetingError.BOT_NOT_LINKED,
                    "Meeting has no bot to remove. meetingId=" + meetingId);
        }
        recallBotClient.leaveCall(meeting.getBotId());
        log.info("봇 강제 내보내기를 요청했습니다. meetingId={} botId={} status={}",
                meetingId, meeting.getBotId(), meeting.getStatus());
    }

    /**
     * 봇이 연결되지 않은 회의 행을 기록상으로만 종료한다.
     * <p>
     * 봇 생성 응답을 받지 못해 botId가 비어 있는 행은 상태 웹훅이 찾아올 수 없어 영원히 진행 중으로 남고, 같은 회의 링크의 재초대까지 막는다. 봇이 있는
     * 행에는 쓰지 않는다. 실제로 통화에 남아 있는 봇을 기록에서만 지우면 과금은 계속되면서 추적만 끊긴다.
     */
    @Transactional
    public void closeRecord(UUID meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        if (meeting.getStatus().isTerminal()) {
            throw new BusinessException(MeetingError.MEETING_ALREADY_ENDED,
                    "Meeting already ended. meetingId=" + meetingId);
        }
        if (meeting.getBotId() != null) {
            throw new BusinessException(MeetingError.BOT_STILL_LINKED,
                    "Meeting still has a bot; remove it from the call instead. meetingId=" + meetingId);
        }
        Instant now = clock.instant();
        meeting.applyStatus(MeetingStatus.DONE, FORCED_CLOSE_SUB_CODE, now, now);
        meetingRepository.save(meeting);
        log.info("봇이 없는 회의 행을 운영자가 종료했습니다. meetingId={} subCode={}", meetingId, FORCED_CLOSE_SUB_CODE);
    }

    private Meeting requireMeeting(UUID meetingId) {
        return meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new BusinessException(MeetingError.MEETING_NOT_FOUND,
                        "Meeting not found. meetingId=" + meetingId));
    }
}
