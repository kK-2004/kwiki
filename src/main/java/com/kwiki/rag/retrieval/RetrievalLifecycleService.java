package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 所有检索路径的权威生命周期排除项。归档的
 * id 集合来自数据库（权威存储）；短生命周期的
 * 内存快照仅用于加速同一窗口内的重复查询。
 * 当快照无法构建时，服务以 fail-closed 方式拒绝——查询绝不能
 * 基于不可信过滤器运行，陈旧缓存也绝不扩大
 * 可见范围。集合保持有界，因为每日清理会物理
 * 删除超过 7 天的归档；异常庞大的集合被视为
 * 不可信并拒绝。
 */
@Component
public class RetrievalLifecycleService {

    /** 过滤器被拒绝之前，已归档 id 集合的硬上限。 */
    public static final int MAX_TRACKED_ARCHIVED = 10_000;

    public static final class LifecycleFilterException extends RuntimeException {
        public LifecycleFilterException(String message) {
            super(message);
        }
    }

    public record Exclusions(Set<Long> archivedKbIds, Set<Long> archivedPageIds,
                             Instant loadedAt) {
        public boolean isEmpty() {
            return archivedKbIds.isEmpty() && archivedPageIds.isEmpty();
        }
    }

    private final JdbcOperations jdbc;
    private final Duration ttl;
    private final AtomicReference<Exclusions> snapshot = new AtomicReference<>();

    public RetrievalLifecycleService(ObjectProvider<JdbcOperations> jdbc,
                                     @Value("${kwiki.retrieval.lifecycle-filter-ttl:5s}")
                                     Duration ttl) {
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.ttl = ttl;
    }

    /**
     * 当前的排除集合，在快照过期时重新加载。当权威
     * 集合无法加载或大得不合理时，抛出
     * {@link LifecycleFilterException}（故障关闭）。
     */
    public Exclusions exclusions() {
        Exclusions current = snapshot.get();
        Instant now = Instant.now();
        if (current != null && current.loadedAt().plus(ttl).isAfter(now)) {
            return current;
        }
        if (jdbc == null) {
            // 离线/单元测试场景：无法得知任何已归档内容，因此
            // 无需排除；生产环境始终运行在 MySQL 之上。
            Exclusions empty = new Exclusions(Set.of(), Set.of(), now);
            snapshot.set(empty);
            return empty;
        }
        try {
            List<Long> kbIds = jdbc.query(
                    "SELECT id FROM knowledge_base WHERE status = 'ARCHIVED'",
                    (rs, row) -> rs.getLong(1));
            List<Long> pageIds = jdbc.query(
                    "SELECT id FROM wiki_page WHERE status = 'ARCHIVED'",
                    (rs, row) -> rs.getLong(1));
            if (kbIds.size() > MAX_TRACKED_ARCHIVED || pageIds.size() > MAX_TRACKED_ARCHIVED) {
                throw new LifecycleFilterException(
                        "archived set too large to build a trusted filter (kb="
                                + kbIds.size() + ", page=" + pageIds.size() + ")");
            }
            Exclusions loaded = new Exclusions(Set.copyOf(kbIds), Set.copyOf(pageIds), now);
            snapshot.set(loaded);
            return loaded;
        } catch (LifecycleFilterException e) {
            throw e;
        } catch (Exception e) {
            throw new LifecycleFilterException("lifecycle filter unavailable: " + e.getMessage());
        }
    }

    /** 用于固定确定性快照的测试钩子。 */
    public void pinForTest(Exclusions pinned) {
        snapshot.set(pinned);
    }
}
