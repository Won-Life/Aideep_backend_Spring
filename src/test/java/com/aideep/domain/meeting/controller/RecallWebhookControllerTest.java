package com.aideep.domain.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.meeting.security.RecallWebhookVerifier;
import com.aideep.domain.meeting.service.MeetingStatusService;
import com.aideep.domain.meeting.service.RecallStatusMapper;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseWrappingAdvice;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class RecallWebhookControllerTest {

    private static final String PATH = "/v1/aideep/api/webhooks/recall/bot-status";
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T01:02:03Z");
    private static final String RECORDING_BODY = """
            {
              "event": "bot.status_change",
              "data": {
                "bot_id": "33333333-3333-4333-8333-333333333333",
                "status": {
                  "code": "in_call_recording",
                  "sub_code": null,
                  "message": null,
                  "created_at": "2026-10-06T01:02:03Z"
                }
              }
            }
            """;

    private static final String LEGACY_RECORDING_BODY = """
            {
              "event": "bot.in_call_recording",
              "data": {
                "bot": { "id": "33333333-3333-4333-8333-333333333333" },
                "data": {
                  "code": "in_call_recording",
                  "sub_code": null,
                  "updated_at": "2026-10-06T01:02:03Z"
                }
              }
            }
            """;

    private RecallWebhookVerifier recallWebhookVerifier;
    private RecallStatusMapper recallStatusMapper;
    private MeetingStatusService meetingStatusService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recallWebhookVerifier = mock(RecallWebhookVerifier.class);
        recallStatusMapper = mock(RecallStatusMapper.class);
        meetingStatusService = mock(MeetingStatusService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RecallWebhookController(
                        recallWebhookVerifier, recallStatusMapper, meetingStatusService, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResponseWrappingAdvice())
                .build();
    }

    @Test
    void appliesMappedStatus() throws Exception {
        when(recallStatusMapper.map("in_call_recording")).thenReturn(Optional.of(MeetingStatus.RECORDING));
        when(meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT))
                .thenReturn(MeetingStatusService.Result.RECORDING_STARTED);

        perform(RECORDING_BODY).andExpect(status().isOk());

        verify(recallWebhookVerifier).verify("msg_1", "1760000000", "v1,signature",
                RECORDING_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(meetingStatusService).apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT);
    }

    @Test
    void rejectsInvalidSignatureWithUnauthorized() throws Exception {
        doThrow(new BusinessException(MeetingError.WEBHOOK_SIGNATURE_INVALID))
                .when(recallWebhookVerifier).verify(any(), any(), any(), any());

        perform(RECORDING_BODY)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("MEETING-004"));

        verifyNoInteractions(recallStatusMapper, meetingStatusService);
    }

    @Test
    void acknowledgesUnknownBot() throws Exception {
        when(recallStatusMapper.map("in_call_recording")).thenReturn(Optional.of(MeetingStatus.RECORDING));
        when(meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT))
                .thenReturn(MeetingStatusService.Result.UNKNOWN_BOT);

        perform(RECORDING_BODY).andExpect(status().isOk());
    }

    @Test
    void acknowledgesStatusCodeTheDomainDoesNotTrack() throws Exception {
        when(recallStatusMapper.map("analysis_done")).thenReturn(Optional.empty());

        perform(RECORDING_BODY.replace("in_call_recording", "analysis_done")).andExpect(status().isOk());

        verifyNoInteractions(meetingStatusService);
    }

    @Test
    void acknowledgesEventsThatAreNotBotStatusChanges() throws Exception {
        perform(RECORDING_BODY.replace("bot.status_change", "transcript.done")).andExpect(status().isOk());

        verifyNoInteractions(recallStatusMapper, meetingStatusService);
    }

    /**
     * 실제 워크스페이스는 상태별 이벤트 이름과 {@code data.bot.id} / {@code data.data.*}를 쓰는 legacy 변형으로 보낸다.
     */
    @Test
    void appliesStatusFromLegacyPerStatusEvent() throws Exception {
        when(recallStatusMapper.map("in_call_recording")).thenReturn(Optional.of(MeetingStatus.RECORDING));
        when(meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT))
                .thenReturn(MeetingStatusService.Result.RECORDING_STARTED);

        perform(LEGACY_RECORDING_BODY).andExpect(status().isOk());

        verify(meetingStatusService).apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT);
    }

    /**
     * legacy 변형에서 payload에 code가 없으면 이벤트 이름이 유일한 상태 정보다.
     */
    @Test
    void readsStatusCodeFromLegacyEventNameWhenPayloadOmitsIt() throws Exception {
        when(recallStatusMapper.map("call_ended")).thenReturn(Optional.of(MeetingStatus.CALL_ENDED));
        when(meetingStatusService.apply(BOT_ID, MeetingStatus.CALL_ENDED, null, CREATED_AT))
                .thenReturn(MeetingStatusService.Result.APPLIED);

        perform("""
                {
                  "event": "bot.call_ended",
                  "data": {
                    "bot": { "id": "33333333-3333-4333-8333-333333333333" },
                    "data": { "updated_at": "2026-10-06T01:02:03Z" }
                  }
                }
                """).andExpect(status().isOk());

        verify(meetingStatusService).apply(BOT_ID, MeetingStatus.CALL_ENDED, null, CREATED_AT);
    }

    @Test
    void acknowledgesUnparseableBody() throws Exception {
        perform("not json").andExpect(status().isOk());

        verifyNoInteractions(recallStatusMapper, meetingStatusService);
    }

    @Test
    void acknowledgesBodyMissingRequiredValues() throws Exception {
        perform("""
                {"event": "bot.status_change", "data": {"bot_id": null, "status": null}}
                """).andExpect(status().isOk());

        verifyNoInteractions(recallStatusMapper, meetingStatusService);
    }

    private org.springframework.test.web.servlet.ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header(RecallWebhookVerifier.ID_HEADER, "msg_1")
                .header(RecallWebhookVerifier.TIMESTAMP_HEADER, "1760000000")
                .header(RecallWebhookVerifier.SIGNATURE_HEADER, "v1,signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
