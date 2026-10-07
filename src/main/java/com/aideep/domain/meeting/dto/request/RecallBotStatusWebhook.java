package com.aideep.domain.meeting.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Recall 봇 생명주기 웹훅 payload. 워크스페이스에 따라 두 변형이 오므로 둘 다 받아 하나로 정규화한다.
 * <ul>
 *     <li>신규: 이벤트 이름이 {@code bot.status_change} 하나이고 {@code data.bot_id} / {@code data.status.*}를 쓴다.</li>
 *     <li>legacy: 이벤트 이름이 상태별({@code bot.in_call_recording} 등)이고 {@code data.bot.id} / {@code data.data.*}를
 *     쓴다.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecallBotStatusWebhook(
        String event,
        Data data
) {

    private static final String EVENT_PREFIX = "bot.";
    private static final String STATUS_CHANGE_EVENT = "bot.status_change";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("bot_id") UUID botId,
            Status status,
            Bot bot,
            @JsonProperty("data") Status legacyStatus
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bot(UUID id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            String code,
            @JsonProperty("sub_code") String subCode,
            String message,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt
    ) {
    }

    /**
     * 봇 상태 변경 이벤트가 아니면(예: 전사 완료 이벤트) false다.
     */
    public boolean isBotStatusEvent() {
        return event != null && event.startsWith(EVENT_PREFIX);
    }

    /**
     * 두 변형을 같은 형태로 정규화한다. 필수 값이 하나라도 없으면 비어 있다.
     */
    public Optional<BotStatus> toBotStatus() {
        if (!isBotStatusEvent() || data == null) {
            return Optional.empty();
        }
        Status resolvedStatus = data.status() != null ? data.status() : data.legacyStatus();
        UUID resolvedBotId = data.botId() != null ? data.botId() : data.bot() == null ? null : data.bot().id();
        String resolvedCode = resolvedStatus == null || resolvedStatus.code() == null
                ? legacyCodeFromEventName()
                : resolvedStatus.code();
        Instant resolvedOccurredAt = resolvedStatus == null ? null
                : resolvedStatus.createdAt() != null ? resolvedStatus.createdAt() : resolvedStatus.updatedAt();
        if (resolvedBotId == null || resolvedCode == null || resolvedOccurredAt == null) {
            return Optional.empty();
        }
        return Optional.of(new BotStatus(resolvedBotId, resolvedCode, resolvedStatus.subCode(), resolvedOccurredAt));
    }

    /**
     * legacy 변형은 이벤트 이름 자체가 상태이므로, payload에 code가 없으면 이름에서 끌어낸다.
     */
    private String legacyCodeFromEventName() {
        return STATUS_CHANGE_EVENT.equals(event) ? null : event.substring(EVENT_PREFIX.length());
    }

    public record BotStatus(UUID botId, String code, String subCode, Instant occurredAt) {
    }
}
