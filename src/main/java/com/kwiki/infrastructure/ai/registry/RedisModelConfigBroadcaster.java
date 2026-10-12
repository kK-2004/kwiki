package com.kwiki.infrastructure.ai.registry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/** 启用 Redis 时以 pub/sub 广播配置版本号；未启用时只依靠轮询兜底。 */
@Configuration
@ConditionalOnProperty(prefix = "kk.common.redis", name = "enabled", havingValue = "true")
public class RedisModelConfigBroadcaster {

    @Bean
    ModelConfigBroadcaster modelConfigBroadcaster(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        return version -> template.convertAndSend(ModelConfigBroadcaster.CHANNEL, Long.toString(version));
    }

    @Bean
    RedisMessageListenerContainer modelConfigListenerContainer(RedisConnectionFactory connectionFactory,
                                                               @Lazy ModelConfigRefresher refresher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                refresher.onRemoteVersion(Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8).trim()));
            } catch (NumberFormatException ignored) {
                // 非法消息直接忽略，轮询会兜底
            }
        }, new ChannelTopic(ModelConfigBroadcaster.CHANNEL));
        return container;
    }
}
