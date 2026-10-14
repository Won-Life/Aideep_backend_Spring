package com.aideep.domain.meeting.controller;

import com.aideep.domain.meeting.dto.response.MeetingUsageResponse;
import com.aideep.domain.meeting.service.MeetingUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정적 대시보드(`/usage-dashboard.html`)가 읽는 집계 엔드포인트의 응답 형태를 고정한다. 화면의 JS가 이 키 이름에 의존한다.
 */
class MeetingUsageEndpointTest {

    private static final UUID MEETING_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final MeetingUsageService meetingUsageService = mock(MeetingUsageService.class);

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new MeetingController(null, null, meetingUsageService, null, null))
                .setControllerAdvice(new com.aideep.global.response.ResponseWrappingAdvice())
                .build();
    }

    @Test
    void returnsHourlyBucketsInsideTheCommonEnvelope() throws Exception {
        when(meetingUsageService.findHourlyUsage(anyInt())).thenReturn(new MeetingUsageResponse(
                Instant.parse("2026-10-14T04:00:00Z"), Instant.parse("2026-10-14T06:00:00Z"),
                List.of(new MeetingUsageResponse.HourBucket(Instant.parse("2026-10-14T04:00:00Z"), 1800, 2),
                        new MeetingUsageResponse.HourBucket(Instant.parse("2026-10-14T05:00:00Z"), 0, 0)),
                List.of(new MeetingUsageResponse.MeetingUsage(MEETING_ID, WORKSPACE_ID, BOT_ID,
                        "https://meet.google.com/abc-defg-hij", "DONE", Instant.parse("2026-10-14T04:00:00Z"),
                        Instant.parse("2026-10-14T04:30:00Z"), 1800, true))));

        mockMvc().perform(get("/v1/aideep/api/meeting/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success.buckets.length()").value(2))
                .andExpect(jsonPath("$.success.buckets[0].usageSeconds").value(1800))
                .andExpect(jsonPath("$.success.buckets[0].meetingCount").value(2))
                .andExpect(jsonPath("$.success.meetings.length()").value(1))
                .andExpect(jsonPath("$.success.meetings[0].usageSeconds").value(1800))
                .andExpect(jsonPath("$.success.meetings[0].status").value("DONE"))
                .andExpect(jsonPath("$.success.meetings[0].billable").value(true))
                .andExpect(jsonPath("$.success.meetings[0].botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.success.meetings[0].meetingUrl")
                        .value("https://meet.google.com/abc-defg-hij"));
    }

    @Test
    void defaultsToTwentyFourHours() throws Exception {
        when(meetingUsageService.findHourlyUsage(anyInt())).thenReturn(new MeetingUsageResponse(
                Instant.EPOCH, Instant.EPOCH, List.of(), List.of()));

        mockMvc().perform(get("/v1/aideep/api/meeting/usage")).andExpect(status().isOk());
        verify(meetingUsageService).findHourlyUsage(24);

        mockMvc().perform(get("/v1/aideep/api/meeting/usage").param("hours", "168"))
                .andExpect(status().isOk());
        verify(meetingUsageService).findHourlyUsage(168);
    }
}
