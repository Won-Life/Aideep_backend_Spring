package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.meeting.config.RecallProperties;
import com.aideep.domain.meeting.dto.request.InviteBotRequest;
import com.aideep.domain.meeting.dto.response.InviteBotResponse;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.global.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class RecallBotClientTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MEETING_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String TRANSCRIPT_WEBHOOK_URL = "https://ai.example.test/webhooks/recall/transcript";

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer httpServer;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 201;
        responseBody = "{\"id\":\"" + BOT_ID + "\"}";
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/v1/bot/", this::handleRequest);
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void createsAudioOnlyBotWithElevenLabsAndPerfectDiarization() throws Exception {
        RecallBotClient recallBotClient = client("recall-key");

        InviteBotResponse result = recallBotClient.invite(request(), MEETING_ID);

        assertThat(result.botId()).isEqualTo(BOT_ID);
        assertThat(authorization.get()).isEqualTo("recall-key");
        JsonNode root = jsonMapper.readTree(requestBody.get());
        assertThat(root.path("meeting_url").asText()).isEqualTo("https://meet.google.com/abc-defg-hij");
        assertThat(root.path("bot_name").asText()).isEqualTo("AIDEEP Notetaker");
        assertThat(root.path("recording_config").has("retention")).isTrue();
        assertThat(root.path("recording_config").path("retention").isNull()).isTrue();
        assertThat(root.path("recording_config").has("video_mixed_mp4")).isTrue();
        assertThat(root.path("recording_config").path("video_mixed_mp4").isNull()).isTrue();
        assertThat(root.path("recording_config").path("audio_mixed_mp3").isObject()).isTrue();
        assertThat(root.path("recording_config").path("transcript").path("provider")
                .path("elevenlabs_streaming").path("model_id").asText()).isEqualTo("scribe_v2_realtime");
        assertThat(root.path("recording_config").path("transcript").path("diarization")
                .path("use_separate_streams_when_available").asBoolean()).isTrue();
        assertThat(root.path("metadata").path("meeting_id").asText()).isEqualTo(MEETING_ID.toString());
        assertThat(root.path("metadata").path("workspace_id").asText()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(root.path("metadata").path("meeting_type").asText()).isEqualTo("GOOGLE");
    }

    @Test
    void registersAiServerAsRealtimeTranscriptWebhook() throws Exception {
        RecallBotClient recallBotClient = client("recall-key", TRANSCRIPT_WEBHOOK_URL);

        recallBotClient.invite(request(), MEETING_ID);

        JsonNode realtimeEndpoints = jsonMapper.readTree(requestBody.get())
                .path("recording_config").path("realtime_endpoints");
        assertThat(realtimeEndpoints.isArray()).isTrue();
        assertThat(realtimeEndpoints).hasSize(1);
        JsonNode endpoint = realtimeEndpoints.get(0);
        assertThat(endpoint.path("type").asText()).isEqualTo("webhook");
        assertThat(endpoint.path("url").asText()).isEqualTo(TRANSCRIPT_WEBHOOK_URL);
        assertThat(endpoint.path("events")).singleElement()
                .satisfies(event -> assertThat(event.asText()).isEqualTo("transcript.data"));
    }

    @Test
    void omitsLanguageCodeSoKoreanAndEnglishAreDetectedAutomatically() throws Exception {
        client("recall-key", TRANSCRIPT_WEBHOOK_URL).invite(request(), MEETING_ID);

        JsonNode elevenLabsStreaming = jsonMapper.readTree(requestBody.get())
                .path("recording_config").path("transcript").path("provider").path("elevenlabs_streaming");
        assertThat(elevenLabsStreaming.has("language_code")).isFalse();
    }

    @Test
    void omitsRealtimeEndpointsWhenAiServerUrlIsNotConfigured() throws Exception {
        client("recall-key").invite(request(), MEETING_ID);

        assertThat(jsonMapper.readTree(requestBody.get()).path("recording_config").has("realtime_endpoints"))
                .isFalse();
    }

    @Test
    void convertsRecallValidationFailureToMeetingError() {
        responseStatus = 400;
        responseBody = "{\"meeting_url\":[\"Enter a valid URL.\"]}";

        assertThatThrownBy(() -> client("recall-key").invite(request(), MEETING_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(MeetingError.BOT_INVITATION_REJECTED));
    }

    @Test
    void convertsRecallCapacityFailureToUnavailableError() {
        responseStatus = 507;
        responseBody = "{}";

        assertThatThrownBy(() -> client("recall-key").invite(request(), MEETING_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(MeetingError.RECALL_UNAVAILABLE));
    }

    @Test
    void rejectsMissingApiKeyBeforeCallingRecall() {
        assertThatThrownBy(() -> client("").invite(request(), MEETING_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECALL_API_KEY");
        assertThat(requestBody.get()).isNull();
    }

    private RecallBotClient client(String apiKey) {
        return client(apiKey, "");
    }

    private RecallBotClient client(String apiKey, String transcriptWebhookUrl) {
        String apiUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/api/v1/bot/";
        RecallProperties recallProperties = new RecallProperties(
                apiUrl, apiKey, "AIDEEP Notetaker", "", transcriptWebhookUrl);
        return new RecallBotClient(recallProperties, RestClient.create());
    }

    private InviteBotRequest request() {
        return new InviteBotRequest(
                "https://meet.google.com/abc-defg-hij", Bottype.GOOGLE, WORKSPACE_ID, NODE_ID);
    }

    private void handleRequest(HttpExchange httpExchange) throws IOException {
        authorization.set(httpExchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(httpExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        httpExchange.getResponseHeaders().set("Content-Type", "application/json");
        httpExchange.sendResponseHeaders(responseStatus, body.length);
        httpExchange.getResponseBody().write(body);
        httpExchange.close();
    }
}
