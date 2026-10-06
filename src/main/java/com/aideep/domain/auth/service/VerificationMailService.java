package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;

@Service
public class VerificationMailService {
    private static final String SERVICE_NAME = "On:Node";

    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;
    private final RedisAuthStore redisAuthStore;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public VerificationMailService(ObjectProvider<JavaMailSender> javaMailSenderProvider, RedisAuthStore redisAuthStore,
                                   AuthProperties authProperties, Clock clock) {
        this.javaMailSenderProvider = javaMailSenderProvider;
        this.redisAuthStore = redisAuthStore;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public void send(String email) {
        JavaMailSender javaMailSender = sender();
        String code = Integer.toString(100000 + secureRandom.nextInt(900000));
        redisAuthStore.saveCode(email, code, clock.millis());
        send(javaMailSender, email, SERVICE_NAME + " 메일 인증 번호",
                "[" + SERVICE_NAME + "] 본인 확인 인증번호 [" + code + "]입니다. 3분 안에 입력해주세요.",
                verificationCodeHtml(code));
    }

    /** 비밀번호 재설정 화면으로 이동하는 1회용 링크를 보낸다. 링크 없이는 재설정할 수 없다. */
    public void sendPasswordResetLink(String email, String resetUrl) {
        send(sender(), email, SERVICE_NAME + " 비밀번호 재설정",
                "[" + SERVICE_NAME + "] 아래 링크에서 비밀번호를 재설정해주세요. 링크는 30분간 유효합니다.\n" + resetUrl,
                passwordResetHtml(resetUrl));
    }

    private JavaMailSender sender() {
        if (authProperties.mailUser() == null || authProperties.mailUser().isBlank()
                || authProperties.mailPass() == null
                || authProperties.mailPass().isBlank())
            throw new IllegalStateException("MAIL_USER and MAIL_PASS are required for email verification");
        JavaMailSender javaMailSender = javaMailSenderProvider.getIfAvailable();
        if (javaMailSender == null) throw new IllegalStateException("SMTP sender is not configured");
        return javaMailSender;
    }

    /**
     * HTML 본문과 평문 본문을 함께 담아 보낸다. HTML을 지원하지 않거나 차단하는 클라이언트에서도 인증번호와 링크를 읽을 수 있어야 한다.
     */
    private void send(JavaMailSender javaMailSender, String email, String subject, String text, String html) {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper mimeMessageHelper =
                    new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            mimeMessageHelper.setFrom(authProperties.mailUser());
            mimeMessageHelper.setTo(email);
            mimeMessageHelper.setSubject(subject);
            mimeMessageHelper.setText(text, html);
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to compose mail: " + subject, e);
        }
        javaMailSender.send(mimeMessage);
    }

    private String verificationCodeHtml(String code) {
        return layout("메일 인증", "아래 인증번호를 입력해 본인 확인을 완료해주세요.", """
                <div style="padding:20px 0;background:#f5f5f7;border-radius:12px;text-align:center;
                            font-size:32px;font-weight:700;letter-spacing:8px;color:#111827;">%s</div>
                """.formatted(code), "인증번호는 3분간 유효합니다. 본인이 요청하지 않았다면 이 메일을 무시해주세요.");
    }

    private String passwordResetHtml(String resetUrl) {
        return layout("비밀번호 재설정", "아래 버튼을 눌러 새 비밀번호를 설정해주세요.", """
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 auto;">
                  <tr>
                    <td style="border-radius:8px;background:#4f46e5;">
                      <a href="%s" target="_blank"
                         style="display:inline-block;padding:14px 32px;font-size:16px;font-weight:600;
                                color:#ffffff;text-decoration:none;">비밀번호 재설정하기</a>
                    </td>
                  </tr>
                </table>
                <p style="margin:24px 0 0;font-size:12px;line-height:1.6;color:#6b7280;word-break:break-all;">
                  버튼이 동작하지 않으면 아래 주소를 브라우저에 붙여넣어주세요.<br>
                  <a href="%s" target="_blank" style="color:#4f46e5;">%s</a>
                </p>
                """.formatted(resetUrl, resetUrl, resetUrl),
                "링크는 30분간 유효하며 한 번만 사용할 수 있습니다. 본인이 요청하지 않았다면 이 메일을 무시해주세요.");
    }

    /** 메일 클라이언트 호환을 위해 table 레이아웃과 인라인 스타일만 사용한다. */
    private String layout(String title, String description, String body, String footer) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f5f5f7;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                       style="background:#f5f5f7;padding:32px 16px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                             style="max-width:480px;background:#ffffff;border-radius:16px;padding:40px 32px;
                                    font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',
                                    'Malgun Gothic',sans-serif;">
                        <tr>
                          <td>
                            <p style="margin:0 0 4px;font-size:13px;font-weight:600;color:#4f46e5;">%s</p>
                            <h1 style="margin:0 0 12px;font-size:22px;font-weight:700;color:#111827;">%s</h1>
                            <p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#4b5563;">%s</p>
                            %s
                            <p style="margin:28px 0 0;padding-top:20px;border-top:1px solid #e5e7eb;
                                      font-size:12px;line-height:1.6;color:#9ca3af;">%s</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(SERVICE_NAME, title, description, body, footer);
    }
}
