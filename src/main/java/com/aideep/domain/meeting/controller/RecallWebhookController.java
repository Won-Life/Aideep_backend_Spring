package com.aideep.domain.meeting.controller;

import com.aideep.domain.meeting.dto.request.RecallBotStatusWebhook;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.security.RecallWebhookVerifier;
import com.aideep.domain.meeting.service.MeetingStatusService;
import com.aideep.domain.meeting.service.RecallStatusMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Recall 봇 생명주기 웹훅 수신 경계.
 * <p>
 * 서명은 원문 바디로 검증해야 하므로 payload DTO로 바인딩하지 않고 {@code byte[]}로 받는다. 영구 실패(파싱 불가, 모르는 봇)는 재시도해도
 * 해결되지 않으므로 200으로 받아 종료하고, 일시적 실패만 예외로 올려 Recall이 재시도하게 한다.
 */
@Slf4j
@RestController
@Tag(name = "Recall 웹훅 컨트롤러")
@RequestMapping("/v1/aideep/api/webhooks/recall")
public class RecallWebhookController {

    private static final int LOGGED_BODY_LIMIT = 1000;

    private final RecallWebhookVerifier recallWebhookVerifier;
    private final RecallStatusMapper recallStatusMapper;
    private final MeetingStatusService meetingStatusService;
    private final ObjectMapper objectMapper;

    public RecallWebhookController(RecallWebhookVerifier recallWebhookVerifier,
                                   RecallStatusMapper recallStatusMapper,
                                   MeetingStatusService meetingStatusService,
                                   ObjectMapper objectMapper) {
        this.recallWebhookVerifier = recallWebhookVerifier;
        this.recallStatusMapper = recallStatusMapper;
        this.meetingStatusService = meetingStatusService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/bot-status")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Recall 봇 상태 변경 웹훅 수신")
    public void handleBotStatus(
            @RequestHeader(value = RecallWebhookVerifier.ID_HEADER, required = false) String webhookId,
            @RequestHeader(value = RecallWebhookVerifier.TIMESTAMP_HEADER, required = false) String webhookTimestamp,
            @RequestHeader(value = RecallWebhookVerifier.SIGNATURE_HEADER, required = false) String webhookSignature,
            @RequestBody byte[] rawBody) {
        recallWebhookVerifier.verify(webhookId, webhookTimestamp, webhookSignature, rawBody);

        Optional<RecallBotStatusWebhook> parsed = parse(webhookId, rawBody);
        if (parsed.isEmpty()) {
            return;
        }
        RecallBotStatusWebhook webhook = parsed.get();
        if (!webhook.isBotStatusEvent()) {
            log.info("봇 상태 이벤트가 아닙니다. webhookId={} event={}", webhookId, webhook.event());
            return;
        }

        Optional<RecallBotStatusWebhook.BotStatus> botStatus = webhook.toBotStatus();
        if (botStatus.isEmpty()) {
            // 신규·legacy 어느 변형으로도 읽히지 않은 경우다. 계약 변경을 바로 확인할 수 있게 원문을 남긴다.
            log.error("Recall 웹훅에서 봇 상태를 읽을 수 없습니다. webhookId={} event={} body={}",
                    webhookId, webhook.event(), truncate(rawBody));
            return;
        }

        RecallBotStatusWebhook.BotStatus status = botStatus.get();
        Optional<MeetingStatus> meetingStatus = recallStatusMapper.map(status.code());
        if (meetingStatus.isEmpty()) {
            log.info("매핑하지 않는 Recall 상태 코드입니다. webhookId={} botId={} code={}",
                    webhookId, status.botId(), status.code());
            return;
        }

        MeetingStatusService.Result result = meetingStatusService.apply(
                status.botId(), meetingStatus.get(), status.subCode(), status.occurredAt());
        log.info("Recall 봇 상태를 반영했습니다. webhookId={} botId={} code={} result={}",
                webhookId, status.botId(), status.code(), result);
    }

    private Optional<RecallBotStatusWebhook> parse(String webhookId, byte[] rawBody) {
        try {
            return Optional.ofNullable(objectMapper.readValue(rawBody, RecallBotStatusWebhook.class));
        } catch (RuntimeException exception) {
            log.error("Recall 웹훅 본문을 해석할 수 없습니다. webhookId={} body={}", webhookId, truncate(rawBody), exception);
            return Optional.empty();
        }
    }

    private String truncate(byte[] rawBody) {
        if (rawBody == null) {
            return "";
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        return body.length() <= LOGGED_BODY_LIMIT ? body : body.substring(0, LOGGED_BODY_LIMIT) + "...";
    }
}
