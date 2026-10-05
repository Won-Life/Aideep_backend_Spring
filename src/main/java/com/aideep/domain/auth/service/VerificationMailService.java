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
        if (authProperties.mailUser() == null || authProperties.mailUser().isBlank()
                || authProperties.mailPass() == null
                || authProperties.mailPass().isBlank())
            throw new IllegalStateException("MAIL_USER and MAIL_PASS are required for email verification");
        JavaMailSender javaMailSender = javaMailSenderProvider.getIfAvailable();
        if (javaMailSender == null) throw new IllegalStateException("SMTP sender is not configured");
        String code = Integer.toString(100000 + secureRandom.nextInt(900000));
        redisAuthStore.saveCode(email, code, clock.millis());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(authProperties.mailUser());
        message.setTo(email);
        message.setSubject("On:Node 메일 인증 번호");
        message.setText("[On:Node] 본인 확인 인증번호 [" + code + "]입니다.");
        javaMailSender.send(message);
    }
}
