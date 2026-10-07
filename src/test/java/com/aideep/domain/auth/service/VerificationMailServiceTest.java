package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

/** 인증번호와 비밀번호 재설정 메일이 HTML 본문과 평문 대체 본문을 함께 담는지 검증한다. */
class VerificationMailServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String RESET_URL = "https://app.example.com/password/reset?token=reset-token";
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private JavaMailSender javaMailSender;
    private RedisAuthStore redisAuthStore;
    private VerificationMailService verificationMailService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        ObjectProvider<JavaMailSender> javaMailSenderProvider = mock(ObjectProvider.class);
        when(javaMailSenderProvider.getIfAvailable()).thenReturn(javaMailSender);
        redisAuthStore = mock(RedisAuthStore.class);
        AuthProperties authProperties = new AuthProperties(null, null, null, null, null, null, null, null,
                "noreply@example.com", "mail-pass", null, null, null);
        // 발송 순서와 시점을 결정적으로 만들기 위해 호출 스레드에서 바로 실행하는 executor를 쓴다.
        verificationMailService = new VerificationMailService(javaMailSenderProvider, redisAuthStore, authProperties,
                new SyncTaskExecutor(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String sentMessage() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captor.getValue().writeTo(out);
        return out.toString();
    }

    @Test
    void sendsPasswordResetLinkAsHtmlButton() throws Exception {
        verificationMailService.sendPasswordResetLink(EMAIL, RESET_URL);

        String raw = sentMessage();
        assertThat(raw).contains("multipart/alternative");
        assertThat(raw).contains("text/html");
        assertThat(raw).contains("text/plain");
        String decoded = decodeQuotedPrintable(raw);
        assertThat(decoded).as("버튼이 재설정 링크를 가리킨다")
                .contains("<a href=\"" + RESET_URL + "\"");
        assertThat(decoded).contains("비밀번호 재설정하기");
    }

    @Test
    void keepsResetLinkReadableInThePlainTextAlternative() throws Exception {
        verificationMailService.sendPasswordResetLink(EMAIL, RESET_URL);

        String decoded = decodeQuotedPrintable(sentMessage());
        assertThat(decoded.indexOf(RESET_URL)).as("평문 본문에도 링크가 남아야 한다").isNotNegative();
        assertThat(decoded).contains("30분간 유효");
    }

    @Test
    void sendsVerificationCodeAsHtmlAndStoresTheSameCode() throws Exception {
        verificationMailService.send(EMAIL);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisAuthStore).saveCode(anyString(), codeCaptor.capture(), anyLong());
        String code = codeCaptor.getValue();
        assertThat(code).hasSize(6);

        String decoded = decodeQuotedPrintable(sentMessage());
        assertThat(decoded).as("HTML 본문과 평문 본문 모두 저장한 인증번호를 담는다").contains(code);
        assertThat(decoded).contains("메일 인증");
    }

    @Test
    void rejectsSendingWhenSmtpCredentialsAreMissing() {
        AuthProperties blankMail = new AuthProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        VerificationMailService withoutCredentials = new VerificationMailService(
                credentialProvider(), mock(RedisAuthStore.class), blankMail, new SyncTaskExecutor(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> withoutCredentials.sendPasswordResetLink(EMAIL, RESET_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_USER");

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void handsSendingToTheMailExecutorInsteadOfTheCallingThread() {
        RecordingTaskExecutor recordingTaskExecutor = new RecordingTaskExecutor();
        VerificationMailService service = new VerificationMailService(credentialProvider(), redisAuthStore,
                new AuthProperties(null, null, null, null, null, null, null, null,
                        "noreply@example.com", "mail-pass", null, null, null),
                recordingTaskExecutor, Clock.fixed(NOW, ZoneOffset.UTC));

        service.sendPasswordResetLink(EMAIL, RESET_URL);

        assertThat(recordingTaskExecutor.submitted).as("발송은 전용 executor로 넘긴다").isEqualTo(1);
        verify(javaMailSender, never()).send(any(MimeMessage.class));

        recordingTaskExecutor.runAll();
        verify(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    void swallowsSendFailureSoTheRequestStillSucceeds() {
        org.mockito.Mockito.doThrow(new MailSendException("smtp down"))
                .when(javaMailSender).send(any(MimeMessage.class));

        verificationMailService.sendPasswordResetLink(EMAIL, RESET_URL);

        verify(javaMailSender).send(any(MimeMessage.class));
    }

    /** 제출된 작업을 보관만 하고 실행하지 않아 비동기 경계를 관찰할 수 있게 한다. */
    private static class RecordingTaskExecutor implements TaskExecutor {
        private final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        private int submitted;

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
            submitted++;
        }

        void runAll() {
            tasks.forEach(Runnable::run);
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> credentialProvider() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(javaMailSender);
        return provider;
    }

    /** 한글과 긴 URL은 quoted-printable로 인코딩되므로 본문을 검증하려면 디코딩해야 한다. */
    private String decodeQuotedPrintable(String raw) {
        String joined = raw.replace("=\r\n", "").replace("=\n", "");
        StringBuilder bytes = new StringBuilder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '=' && i + 2 < joined.length() && isHex(joined.charAt(i + 1)) && isHex(joined.charAt(i + 2))) {
                out.write(Integer.parseInt(joined.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        bytes.append(out.toString(java.nio.charset.StandardCharsets.UTF_8));
        return bytes.toString();
    }

    private boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
