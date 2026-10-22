package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalControlBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Redis Pub/Sub 上的评测控制总线：频道 {@code kwiki:eval:control}，消息 JSON
 * {@code {"runId":…,"action":"…"}}。所有实例订阅，执行实例收到（如取消）立即生效；
 * 消息丢失时由执行实例的心跳轮询（读取取消标志）兜底。
 */
public class RedisEvalControlBus implements EvalControlBus, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisEvalControlBus.class);
    static final String CHANNEL = "kwiki:eval:control";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisMessageListenerContainer container;
    private final List<ControlHandler> handlers = new CopyOnWriteArrayList<>();

    public RedisEvalControlBus(RedisConnectionFactory connectionFactory, ObjectMapper mapper) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.mapper = mapper;
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> dispatch(message.getBody()), new ChannelTopic(CHANNEL));
        container.start();
    }

    private void dispatch(byte[] body) {
        if (body == null) return;
        try {
            JsonNode node = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String runId = node.path("runId").asText(null);
            String action = node.path("action").asText(null);
            if (runId == null || action == null) return;
            for (ControlHandler handler : handlers) {
                try {
                    handler.onControl(runId, action);
                } catch (RuntimeException e) {
                    log.warn("eval control handler failed runId={} action={} reason={}", runId, action, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("eval control message ignored reason={}", e.getMessage());
        }
    }

    @Override
    public void publish(String runId, String action) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("runId", runId);
        message.put("action", action);
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(message));
        } catch (Exception e) {
            // 通知失败不影响操作结果：心跳轮询取消标志兜底
            log.warn("eval control publish failed runId={} action={} reason={}", runId, action, e.getMessage());
        }
    }

    @Override
    public Runnable subscribe(ControlHandler handler) {
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    @Override
    public void destroy() {
        container.stop();
    }
}
