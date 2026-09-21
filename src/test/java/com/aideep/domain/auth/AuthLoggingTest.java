package com.aideep.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class AuthLoggingTest {
    @Test
    void yamlLevelsShowExpectedFailuresOnlyInDevelopment() throws Exception {
        var yamlPropertySourceLoader = new YamlPropertySourceLoader();
        var base = yamlPropertySourceLoader.load("base", new ClassPathResource("application.yml")).getFirst();
        var dev = yamlPropertySourceLoader.load("dev", new ClassPathResource("application-dev.yml")).getFirst();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previous = logger.getLevel();
        var listAppender = new ListAppender<ILoggingEvent>();
        listAppender.start();
        logger.addAppender(listAppender);
        try {
            var globalExceptionHandler = new GlobalExceptionHandler();
            logger.setLevel(Level.toLevel(
                    (String) dev.getProperty("logging.level.com.aideep.global.exception.GlobalExceptionHandler")));
            globalExceptionHandler.handleBusinessException(new BusinessException(AuthError.TOKEN_REVOKED));
            assertThat(listAppender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG);
            listAppender.list.clear();
            logger.setLevel(Level.toLevel(
                    (String) base.getProperty("logging.level.com.aideep.global.exception.GlobalExceptionHandler")));
            globalExceptionHandler.handleBusinessException(new BusinessException(AuthError.TOKEN_REVOKED));
            globalExceptionHandler.handleUnexpectedException(new IllegalStateException("test infrastructure failure"));
            assertThat(listAppender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
            assertThat(listAppender.list.getFirst().getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(listAppender);
            logger.setLevel(previous);
            listAppender.stop();
        }
    }
}
