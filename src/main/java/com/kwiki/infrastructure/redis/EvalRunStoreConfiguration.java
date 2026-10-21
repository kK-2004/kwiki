package com.kwiki.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalRunStore;
import com.kwiki.eval.InMemoryEvalRunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 启用 Redis（kk.common.redis.enabled=true）时评测 run 持久化到 Redis，否则退化为进程内存储。 */
@Configuration
public class EvalRunStoreConfiguration {

    @Bean
    EvalRunStore evalRunStore(ObjectProvider<RedisConnectionFactory> connectionFactory, ObjectMapper mapper) {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null) return new InMemoryEvalRunStore();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisEvalRunStore(template, mapper);
    }
}
