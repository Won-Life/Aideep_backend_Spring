package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;

@Service
public class VerificationMailService {
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
        send(javaMailSender, email, "On:Node 메일 인증 번호", "[On:Node] 본인 확인 인증번호 [" + code + "]입니다.");
    }

    /** 비밀번호 재설정 화면으로 이동하는 1회용 링크를 보낸다. 링크 없이는 재설정할 수 없다. */
    public void sendPasswordResetLink(String email, String resetUrl) {
        send(sender(), email, "On:Node 비밀번호 재설정",
                "[On:Node] 아래 링크에서 비밀번호를 재설정해주세요. 링크는 30분간 유효합니다.\n" + resetUrl);
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

    private void send(JavaMailSender javaMailSender, String email, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(authProperties.mailUser());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text);
        javaMailSender.send(message);
    }
}
