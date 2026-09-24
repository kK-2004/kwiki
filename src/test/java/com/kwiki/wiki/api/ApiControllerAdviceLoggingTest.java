package com.kwiki.wiki.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.kk2004.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiControllerAdviceLoggingTest {

    @Test
    void handledBusinessExceptionIsLoggedAndKeepsTheSharedEnvelope() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiControllerAdvice.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new ApiControllerAdvice()
                    .businessException(new NotFoundException("wiki不存在"));

            assertThat(response.getCode()).isEqualTo(404);
            assertThat(response.getMessage()).isEqualTo("wiki不存在");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("handled business exception")
                        .contains("NotFoundException")
                        .contains("code=404")
                        .contains("wiki不存在");
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unreadableRequestBodyIsLoggedAndKeepsTheSharedEnvelope() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiControllerAdvice.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var exception = new HttpMessageNotReadableException("malformed JSON");
            var response = new ApiControllerAdvice().messageNotReadable(exception);

            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).isEqualTo("请求体格式错误");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("handled unreadable-request-body exception")
                        .contains("请求体格式错误");
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
