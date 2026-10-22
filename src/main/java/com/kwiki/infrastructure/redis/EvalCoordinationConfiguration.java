package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalClusterSlots;
import com.kwiki.eval.EvalControlBus;
import com.kwiki.eval.EvalInstanceId;
import com.kwiki.eval.EvalRunLocks;
import com.kwiki.eval.EvalWorkQueue;
import com.kwiki.eval.InMemoryEvalClusterSlots;
import com.kwiki.eval.InMemoryEvalControlBus;
import com.kwiki.eval.InMemoryEvalRunLocks;
import com.kwiki.eval.InMemoryEvalWorkQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 评测执行协调设施的装配：启用 Redis（有连接工厂）时用 Stream 队列、ZSET 名额、
 * Pub/Sub 控制总线与分布式 run 锁，否则全部退化为进程内实现（单实例排队执行）。
 */
@Configuration
public class EvalCoordinationConfiguration {

    @Bean
    EvalWorkQueue evalWorkQueue(ObjectProvider<RedisConnectionFactory> connectionFactory,
                                EvalInstanceId instanceId) {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) return new InMemoryEvalWorkQueue();
        return new RedisEvalWorkQueue(template(factory), instanceId);
    }

    @Bean
    EvalClusterSlots evalClusterSlots(ObjectProvider<RedisConnectionFactory> connectionFactory) {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) return new InMemoryEvalClusterSlots();
        return new RedisEvalClusterSlots(template(factory));
    }

    @Bean
    EvalControlBus evalControlBus(ObjectProvider<RedisConnectionFactory> connectionFactory, ObjectMapper mapper) {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) return new InMemoryEvalControlBus();
        return new RedisEvalControlBus(factory, mapper);
    }

    @Bean
    EvalRunLocks evalRunLocks(KwikiDistributedLocks locks) {
        return locks.isAvailable() ? new RedisEvalRunLocks(locks) : new InMemoryEvalRunLocks();
    }

    static StringRedisTemplate template(RedisConnectionFactory factory) {
        StringRedisTemplate redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}
