package com.aideep.domain.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 메일 발송 전용 스레드 풀. SMTP는 발송마다 연결과 TLS 핸드셰이크를 새로 하므로 요청 스레드에서 떼어낸다.
     * <p>
     * 큐가 가득 차면 호출 스레드가 직접 발송한다. 응답은 느려지지만 요청이 조용히 버려지지는 않는다. 종료 시에는 큐에 남은 발송을 마칠 때까지 기다린다.
     * <p>
     * 이 애플리케이션의 유일한 {@code Executor} 빈이므로 Spring Boot 기본 {@code applicationTaskExecutor}를 대체한다. 다른 용도의 비동기 작업을
     * 추가할 때는 메일 발송과 자원을 나눠 쓰지 않도록 별도 풀을 정의한다.
     */
    @Bean
    ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor mailTaskExecutor = new ThreadPoolTaskExecutor();
        mailTaskExecutor.setCorePoolSize(2);
        mailTaskExecutor.setMaxPoolSize(4);
        mailTaskExecutor.setQueueCapacity(100);
        mailTaskExecutor.setThreadNamePrefix("mail-");
        mailTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        mailTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        mailTaskExecutor.setAwaitTerminationSeconds(20);
        return mailTaskExecutor;
    }
}
