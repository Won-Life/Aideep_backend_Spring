package com.aideep.domain.meeting.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 회의 사용량 대시보드(정적 HTML)가 읽는 집계. 금액은 단가가 설정값이라 화면에서 계산한다.
 *
 * @param buckets  시간 버킷 오름차순. 사용량이 없는 시간도 0으로 채워 보낸다.
 * @param meetings 기간과 겹친 회의 목록. 시작 시각 내림차순이며, 사용 초의 합은 {@code buckets}의 합과 같다.
 */
public record MeetingUsageResponse(Instant from, Instant to, List<HourBucket> buckets, List<MeetingUsage> meetings) {

    /**
     * @param usageSeconds 그 시간대에 봇이 활성이었던 초. 한 회의가 여러 시간에 걸치면 겹친 만큼 나눠 담는다.
     * @param meetingCount 그 시간대에 시작된 회의 수.
     */
    public record HourBucket(Instant hour, long usageSeconds, int meetingCount) {
    }

    /**
     * 회의 한 건의 과금 시간.
     *
     * @param botId        Recall 봇 식별자. 봇 생성 응답을 받지 못했으면 null이고, 그런 행은 상태 웹훅이 영원히 오지 않는다.
     * @param startedAt    과금 시작으로 쓰는 봇 생성 요청 시각.
     * @param endedAt      종료 시각. 진행 중이면 null이다.
     * @param usageSeconds 조회 기간과 겹친 과금 초. 기간 밖으로 삐져나간 부분은 빼므로 시간별 합계와 어긋나지 않는다.
     * @param billable     Recall이 과금하는 회의인지. {@code fatal}로 끝난 회의는 false이고 사용 초가 0이다.
     */
    public record MeetingUsage(
            UUID meetingId,
            UUID workspaceId,
            UUID botId,
            String meetingUrl,
            String status,
            Instant startedAt,
            Instant endedAt,
            long usageSeconds,
            boolean billable
    ) {
    }
}
