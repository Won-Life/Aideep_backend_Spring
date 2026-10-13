package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.response.MeetingUsageResponse;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회의 기록으로 시간별 봇 사용량을 집계한다.
 * <p>
 * Recall의 사용량 API는 기간 총합(초) 하나만 주고 시간별 분해나 금액을 주지 않으므로, 시간별 수치는 우리 {@code meetings} 기록으로 만든다. Recall은
 * 봇이 회의 URL을 여는 시점부터 종료까지(대기실 포함) 과금하는데 그 전이 시각은 저장하지 않으므로, 봇 생성 요청 시각을 시작으로 쓰는 근사치다. {@code fatal}로
 * 끝난 회의는 Recall이 과금하지 않으므로 제외한다.
 */
@Service
@Transactional(readOnly = true)
public class MeetingUsageService {

    private static final int MAX_HOURS = 24 * 30;

    private final MeetingRepository meetingRepository;
    private final Clock clock;

    public MeetingUsageService(MeetingRepository meetingRepository, Clock clock) {
        this.meetingRepository = meetingRepository;
        this.clock = clock;
    }

    public MeetingUsageResponse findHourlyUsage(int hours) {
        int window = Math.clamp(hours, 1, MAX_HOURS);
        Instant now = clock.instant();
        Instant to = now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(window, ChronoUnit.HOURS);

        Map<Instant, long[]> accumulated = new LinkedHashMap<>();
        List<MeetingUsageResponse.MeetingUsage> meetings = new ArrayList<>();
        for (Meeting meeting : meetingRepository.findOverlapping(from, to)) {
            boolean billable = meeting.getStatus() != MeetingStatus.FAILED;
            Instant start = meeting.getCreatedAt();
            // 아직 끝나지 않은 회의는 지금까지만 사용한 것으로 센다.
            Instant end = meeting.getEndedAt() == null ? now : meeting.getEndedAt();
            long seconds = 0;
            if (billable && start != null && start.isBefore(end)) {
                Instant clippedStart = start.isBefore(from) ? from : start;
                Instant clippedEnd = end.isAfter(to) ? to : end;
                seconds = spread(accumulated, clippedStart, clippedEnd);
                if (!start.isBefore(from) && start.isBefore(to)) {
                    bucket(accumulated, start.truncatedTo(ChronoUnit.HOURS))[1] += 1;
                }
            }
            // 과금되지 않는 회의도 목록에는 남긴다. 사용 초가 0이라 합계는 달라지지 않는다.
            meetings.add(new MeetingUsageResponse.MeetingUsage(meeting.getId(), meeting.getWorkspaceId(),
                    meeting.getMeetingUrl(), meeting.getStatus().name(), start, meeting.getEndedAt(), seconds,
                    billable));
        }
        meetings.sort(Comparator.comparing(MeetingUsageResponse.MeetingUsage::startedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<MeetingUsageResponse.HourBucket> buckets = new ArrayList<>();
        for (Instant hour = from; hour.isBefore(to); hour = hour.plus(1, ChronoUnit.HOURS)) {
            long[] values = accumulated.getOrDefault(hour, new long[2]);
            buckets.add(new MeetingUsageResponse.HourBucket(hour, values[0], (int) values[1]));
        }
        return new MeetingUsageResponse(from, to, buckets, meetings);
    }

    /**
     * 과금 구간을 시간 버킷에 나눠 담고, 그 회의가 기간 안에서 쓴 총 초를 돌려준다.
     */
    private long spread(Map<Instant, long[]> accumulated, Instant start, Instant end) {
        long total = 0;
        for (Instant hour = start.truncatedTo(ChronoUnit.HOURS); hour.isBefore(end);
                hour = hour.plus(1, ChronoUnit.HOURS)) {
            Instant hourEnd = hour.plus(1, ChronoUnit.HOURS);
            Instant overlapStart = hour.isBefore(start) ? start : hour;
            Instant overlapEnd = hourEnd.isBefore(end) ? hourEnd : end;
            long seconds = Duration.between(overlapStart, overlapEnd).getSeconds();
            if (seconds > 0) {
                bucket(accumulated, hour)[0] += seconds;
                total += seconds;
            }
        }
        return total;
    }

    private long[] bucket(Map<Instant, long[]> accumulated, Instant hour) {
        return accumulated.computeIfAbsent(hour, key -> new long[2]);
    }
}
