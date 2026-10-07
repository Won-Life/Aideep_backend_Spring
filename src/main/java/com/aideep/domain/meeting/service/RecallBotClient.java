package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.config.RecallProperties;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.global.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class RecallBotClient {

    private static final String ELEVENLABS_MODEL = "scribe_v2_realtime";

    private final RecallProperties recallProperties;
    private final RestClient restClient;

    @Autowired
    public RecallBotClient(RecallProperties recallProperties) {
        this(recallProperties, createRestClient());
    }

    RecallBotClient(RecallProperties recallProperties, RestClient restClient) {
        this.recallProperties = recallProperties;
        this.restClient = restClient;
    }

    private static RestClient createRestClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    public InviteBotResponse invite(InviteBotRequest inviteBotRequest, UUID meetingId) {
        requireConfiguration();
        RecallBotResponse recallBotResponse;
        try {
            recallBotResponse = restClient.post()
                    .uri(recallProperties.apiUrl())
                    .header("Authorization", recallProperties.apiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createRequest(inviteBotRequest, meetingId))
                    .retrieve()
                    .body(RecallBotResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                log.debug(String.valueOf(exception));
                throw new BusinessException(MeetingError.BOT_INVITATION_REJECTED);
            }
            throw new BusinessException(MeetingError.RECALL_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            throw new BusinessException(MeetingError.RECALL_UNAVAILABLE);
        }

        if (recallBotResponse == null || recallBotResponse.id() == null) {
            throw new BusinessException(MeetingError.RECALL_RESPONSE_INVALID);
        }
        return new InviteBotResponse(recallBotResponse.id());
    }

    private RecallCreateBotRequest createRequest(InviteBotRequest inviteBotRequest, UUID meetingId) {
        ElevenLabsStreaming elevenLabsStreaming = new ElevenLabsStreaming(ELEVENLABS_MODEL);
        Transcript transcript = new Transcript(
                new TranscriptProvider(elevenLabsStreaming), new Diarization(true));
        RecordingConfig recordingConfig = new RecordingConfig(null, null, Map.of(), transcript);
        // meeting_id는 응답 처리 실패로 bot_id를 저장하지 못한 봇을 회의로 되짚는 유일한 수단이다.
        Map<String, String> metadata = Map.of(
                "meeting_id", meetingId.toString(),
                "workspace_id", inviteBotRequest.workspaceId().toString(),
                "meeting_type", inviteBotRequest.type().name());
        return new RecallCreateBotRequest(
                inviteBotRequest.url(), recallProperties.botName(), recordingConfig, metadata);
    }

    private void requireConfiguration() {
        if (isBlank(recallProperties.apiUrl()) || isBlank(recallProperties.apiKey())
                || isBlank(recallProperties.botName())) {
            throw new IllegalStateException("RECALL_API_URL, RECALL_API_KEY and RECALL_BOT_NAME are required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RecallCreateBotRequest(
            @JsonProperty("meeting_url") String meetingUrl,
            @JsonProperty("bot_name") String botName,
            @JsonProperty("recording_config") RecordingConfig recordingConfig,
            Map<String, String> metadata
    ) {
    }

    /**
     * Zero Data Retention을 사용하므로 retention을 명시적으로 null로 직렬화한다.
     * 필드를 생략하면 계정 기본 보존 정책이 적용돼 녹화 미디어가 Recall에 저장된다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record RecordingConfig(
            @JsonProperty("retention") Object retention,
            @JsonProperty("video_mixed_mp4") Object videoMixedMp4,
            @JsonProperty("audio_mixed_mp3") Map<String, Object> audioMixedMp3,
            Transcript transcript
    ) {
    }

    private record Transcript(TranscriptProvider provider, Diarization diarization) {
    }

    private record TranscriptProvider(
            @JsonProperty("elevenlabs_streaming") ElevenLabsStreaming elevenLabsStreaming
    ) {
    }

    private record ElevenLabsStreaming(@JsonProperty("model_id") String modelId) {
    }

    private record Diarization(
            @JsonProperty("use_separate_streams_when_available") boolean useSeparateStreamsWhenAvailable
    ) {
    }

    private record RecallBotResponse(UUID id) {
    }
}
