package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.response.MeetingUsageResponse;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingUsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T05:30:00Z");

    private final MeetingRepository meetingRepository = mock(MeetingRepository.class);

    @Test
    void splitsOneMeetingAcrossTheHoursItOverlaps() {
        given(meeting(Instant.parse("2026-10-14T03:50:00Z"), Instant.parse("2026-10-14T04:20:00Z"),
                MeetingStatus.DONE));

        List<MeetingUsageResponse.HourBucket> buckets = service().findHourlyUsage(24).buckets();

        assertThat(secondsAt(buckets, "2026-10-14T03:00:00Z")).isEqualTo(600);
        assertThat(secondsAt(buckets, "2026-10-14T04:00:00Z")).isEqualTo(1200);
    }

    @Test
    void countsAMeetingOnlyInTheHourItStarted() {
        given(meeting(Instant.parse("2026-10-14T03:50:00Z"), Instant.parse("2026-10-14T05:10:00Z"),
                MeetingStatus.DONE));

        List<MeetingUsageResponse.HourBucket> buckets = service().findHourlyUsage(24).buckets();

        assertThat(meetingsAt(buckets, "2026-10-14T03:00:00Z")).isEqualTo(1);
        assertThat(meetingsAt(buckets, "2026-10-14T04:00:00Z")).isZero();
    }

    @Test
    void capsAnOngoingMeetingAtNow() {
        given(meeting(Instant.parse("2026-10-14T05:00:00Z"), null, MeetingStatus.RECORDING));

        List<MeetingUsageResponse.HourBucket> buckets = service().findHourlyUsage(24).buckets();

        assertThat(secondsAt(buckets, "2026-10-14T05:00:00Z")).isEqualTo(1800);
    }

    @Test
    void excludesFailedMeetingsBecauseRecallDoesNotBillThem() {
        given(meeting(Instant.parse("2026-10-14T04:00:00Z"), Instant.parse("2026-10-14T04:30:00Z"),
                MeetingStatus.FAILED));

        MeetingUsageResponse response = service().findHourlyUsage(24);

        assertThat(response.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.usageSeconds()).isZero();
            assertThat(bucket.meetingCount()).isZero();
        });
        // 목록에는 남겨 왜 비용이 0인지 보이게 한다.
        assertThat(response.meetings()).singleElement().satisfies(meeting -> {
            assertThat(meeting.billable()).isFalse();
            assertThat(meeting.usageSeconds()).isZero();
            assertThat(meeting.status()).isEqualTo("FAILED");
        });
    }

    @Test
    void reportsPerMeetingUsageThatSumsToTheHourlyTotal() {
        given(meeting(Instant.parse("2026-10-14T03:50:00Z"), Instant.parse("2026-10-14T04:20:00Z"),
                        MeetingStatus.DONE),
                meeting(Instant.parse("2026-10-14T05:00:00Z"), null, MeetingStatus.RECORDING));

        MeetingUsageResponse response = service().findHourlyUsage(24);

        assertThat(response.meetings()).extracting(MeetingUsageResponse.MeetingUsage::usageSeconds)
                .containsExactly(1800L, 1800L);
        long perMeeting = response.meetings().stream()
                .mapToLong(MeetingUsageResponse.MeetingUsage::usageSeconds).sum();
        long hourly = response.buckets().stream()
                .mapToLong(MeetingUsageResponse.HourBucket::usageSeconds).sum();
        assertThat(perMeeting).isEqualTo(hourly);
    }

    @Test
    void ordersMeetingsByStartDescendingAndMarksTheOngoingOne() {
        given(meeting(Instant.parse("2026-10-14T01:00:00Z"), Instant.parse("2026-10-14T01:30:00Z"),
                        MeetingStatus.DONE),
                meeting(Instant.parse("2026-10-14T05:00:00Z"), null, MeetingStatus.RECORDING));

        List<MeetingUsageResponse.MeetingUsage> meetings = service().findHourlyUsage(24).meetings();

        assertThat(meetings).extracting(MeetingUsageResponse.MeetingUsage::startedAt)
                .containsExactly(Instant.parse("2026-10-14T05:00:00Z"), Instant.parse("2026-10-14T01:00:00Z"));
        assertThat(meetings.getFirst().endedAt()).isNull();
        assertThat(meetings.getLast().endedAt()).isEqualTo(Instant.parse("2026-10-14T01:30:00Z"));
    }

    @Test
    void clipsPerMeetingUsageToTheRequestedWindow() {
        given(meeting(Instant.parse("2026-10-13T05:30:00Z"), Instant.parse("2026-10-13T06:30:00Z"),
                MeetingStatus.DONE));

        // 조회 창은 2026-10-13T06:00:00Z 부터다. 창 밖 30분은 빠져야 시간별 합계와 어긋나지 않는다.
        assertThat(service().findHourlyUsage(24).meetings()).singleElement()
                .satisfies(meeting -> assertThat(meeting.usageSeconds()).isEqualTo(1800));
    }

    @Test
    void returnsOneBucketPerHourSoTheChartHasNoGaps() {
        given();

        MeetingUsageResponse response = service().findHourlyUsage(24);

        assertThat(response.buckets()).hasSize(24);
        assertThat(response.from()).isEqualTo(Instant.parse("2026-10-13T06:00:00Z"));
        assertThat(response.to()).isEqualTo(Instant.parse("2026-10-14T06:00:00Z"));
        assertThat(response.buckets().getLast().hour()).isEqualTo(Instant.parse("2026-10-14T05:00:00Z"));
    }

    @Test
    void clampsTheRequestedWindowToASaneRange() {
        given();

        assertThat(service().findHourlyUsage(0).buckets()).hasSize(1);
        assertThat(service().findHourlyUsage(100_000).buckets()).hasSize(24 * 30);
    }

    private void given(Meeting... meetings) {
        when(meetingRepository.findOverlapping(any(), any())).thenReturn(List.of(meetings));
    }

    private MeetingUsageService service() {
        return new MeetingUsageService(meetingRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long secondsAt(List<MeetingUsageResponse.HourBucket> buckets, String hour) {
        return bucket(buckets, hour).usageSeconds();
    }

    private int meetingsAt(List<MeetingUsageResponse.HourBucket> buckets, String hour) {
        return bucket(buckets, hour).meetingCount();
    }

    private MeetingUsageResponse.HourBucket bucket(List<MeetingUsageResponse.HourBucket> buckets, String hour) {
        return buckets.stream().filter(bucket -> bucket.hour().equals(Instant.parse(hour)))
                .findFirst().orElseThrow();
    }

    private Meeting meeting(Instant createdAt, Instant endedAt, MeetingStatus status) {
        Meeting meeting = Meeting.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://meet.test/abc", Bottype.GOOGLE, createdAt);
        meeting.applyStatus(status, null, endedAt == null ? createdAt : endedAt, createdAt);
        return meeting;
    }
}
