package com.aideep.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 둘 이상의 도메인이 사용하는 시간 소스다. 테스트에서 고정 시각을 주입할 수 있게 항상 주입 대상으로 사용한다.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
