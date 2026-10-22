package com.kwiki.infrastructure.ai.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCallErrorLoggerTest {
    final Logger logger = (Logger) LoggerFactory.getLogger(ModelCallErrorLogger.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    static ChatModelErrorContext context(Throwable error) {
        return new ChatModelErrorContext(error, ChatRequest.builder().messages(UserMessage.from("问题")).build(),
                ModelProvider.OPEN_AI, new HashMap<>());
    }

    @Test
    void providerRejectionIsLoggedWithStatusAndResponseBody() {
        var body = "{\"error\":{\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}}";
        new ModelCallErrorLogger("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus")
                .onError(context(new RuntimeException("wrapped", new HttpException(400, body))));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("model=qwen-plus")
                .contains("endpoint=dashscope.aliyuncs.com")
                .contains("status=400")
                .contains("Arrearage")
                .contains("account is in good standing");
    }

    @Test
    void secretsInProviderMessagesAreRedactedAndLongBodiesTruncated() {
        var message = "Incorrect API key provided: sk-abcdefghijklmnopqrstuvwxyz123456 " + "x".repeat(3000);
        new ModelCallErrorLogger("https://api.example.com/v1", "m")
                .onError(context(new AuthenticationException(message)));

        String logged = appender.list.getFirst().getFormattedMessage();
        assertThat(logged).contains("errorType=AuthenticationException")
                .doesNotContain("sk-abcdefghijklmnopqrstuvwxyz123456");
        assertThat(logged.length()).isLessThan(2000);
    }

    @Test
    void errorsWithoutHttpStatusAreStillLogged() {
        new ModelCallErrorLogger("http://localhost:1234/v1", "local")
                .onError(context(new java.net.ConnectException("Connection refused")));

        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("status=-").contains("ConnectException").contains("Connection refused");
    }

    @Test
    void jsonSchemaRejectionIsAnExpectedFallbackLoggedAtInfo() {
        var request = ChatRequest.builder().messages(UserMessage.from("问题"))
                .responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.builder()
                        .type(dev.langchain4j.model.chat.request.ResponseFormatType.JSON)
                        .jsonSchema(dev.langchain4j.model.chat.request.json.JsonSchema.builder().name("q")
                                .rootElement(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build())
                                .build())
                        .build())
                .build();
        new ModelCallErrorLogger("https://x/v1", "deepseek").onError(new ChatModelErrorContext(
                new HttpException(400, "model request rejected: This response_format type is unavailable now"),
                request, ModelProvider.OPEN_AI, new HashMap<>()));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("json_schema").contains("deepseek");
    }

    @Test
    void cancelledCallsAreNotErrors() {
        logger.setLevel(Level.DEBUG);
        try {
            new ModelCallErrorLogger("https://x/v1", "m").onError(context(
                    new RuntimeException("Interrupted during action execution", new InterruptedException())));
            new ModelCallErrorLogger("https://x/v1", "m").onError(context(
                    new com.kwiki.rag.orchestration.RunFailure("cancelled")));

            assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        } finally {
            logger.setLevel(null);
        }
    }
}
