package com.kwiki.infrastructure.redis;

/**
 * Lua 脚本前导：以 Redis 服务器时间（毫秒）定义 {@code now}。跨实例的归属与名额判断只认这一个时钟，
 * 避免各实例本地时钟偏差导致误判过期。旧版 Redis（&lt; 5）须先切换为效果复制才允许在 TIME 之后写入。
 */
final class RedisLuaTime {

    static final String SERVER_NOW = """
            if redis.replicate_commands then pcall(redis.replicate_commands) end
            local serverTime = redis.call('TIME')
            local now = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)
            """;

    private RedisLuaTime() {
    }
}
