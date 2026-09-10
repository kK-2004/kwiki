package com.kwiki.wiki.access;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 由 scope_version 表支撑的、各知识库单调递增的作用域版本。成员关系变更会
 * 使版本号递增，进而让在途请求失效，以便出站守卫将其中止。当没有可用
 * 的 JDBC 模板时（单元/开发环境），内存兜底保证服务可用；生产环境始终
 * 运行在 MySQL 上。
 */
@Service
public class ScopeVersionService {

    private static final long INITIAL_VERSION = 1L;

    private final JdbcOperations jdbc;
    private final Map<Long, AtomicLong> inMemoryFallback = new ConcurrentHashMap<>();

    public ScopeVersionService(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 知识库的当前版本（首次成员关系变更前为 1）。 */
    public long current(long kbId) {
        if (jdbc == null) {
            return inMemory(kbId).get();
        }
        try {
            Long version = jdbc.queryForObject(
                    "SELECT version FROM scope_version WHERE kb_id = ?", Long.class, kbId);
            return version == null ? INITIAL_VERSION : version;
        } catch (EmptyResultDataAccessException e) {
            return INITIAL_VERSION;
        }
    }

    /**
     * 原子地推进版本号。守卫式更新 + 插入重试，在并发成员关系变更下
     * 仍保持值的严格单调递增。
     */
    public long bump(long kbId) {
        if (jdbc == null) {
            return inMemory(kbId).incrementAndGet();
        }
        while (true) {
            long observed = current(kbId);
            int updated = jdbc.update(
                    "UPDATE scope_version SET version = version + 1 WHERE kb_id = ? AND version = ?",
                    kbId, observed);
            if (updated > 0) {
                return observed + 1;
            }
            try {
                jdbc.update("INSERT INTO scope_version (kb_id, version) VALUES (?, ?)",
                        kbId, INITIAL_VERSION);
            } catch (DuplicateKeyException raceLost) {
                // 另一写入方已先创建该行；重试守卫式更新
            }
        }
    }

    private AtomicLong inMemory(long kbId) {
        return inMemoryFallback.computeIfAbsent(kbId, k -> new AtomicLong(INITIAL_VERSION));
    }
}
