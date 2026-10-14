package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面检索索引的就绪状态：页面发布后即可阅读，但视觉摘要与 ES 分块由
 * 索引任务异步建立，期间搜索与问答尚未覆盖本页。以该页最近一次
 * UPSERT 任务的聚合状态为准；没有任务（历史数据）视为已就绪。
 */
@Service
public class PageIndexStatusService {

    public enum IndexStatus { INDEXING, READY, FAILED }

    public record PageIndexStatus(IndexStatus status) {
    }

    private final JdbcOperations jdbc;
    private final PageRevisionService pageRevisions;

    public PageIndexStatusService(ObjectProvider<JdbcOperations> jdbc, PageRevisionService pageRevisions) {
        this.jdbc = jdbc.getIfAvailable();
        this.pageRevisions = pageRevisions;
    }

    public PageIndexStatus status(CurrentUser user, long kbId, long pageId) {
        // 复用阅读授权与“页面存在且已发布”的校验。
        pageRevisions.publishedContent(user, kbId, pageId);
        return new PageIndexStatus(statuses(List.of(pageId)).getOrDefault(pageId, IndexStatus.READY));
    }

    /** 批量查询（调用方负责授权），供导入列表展示；缺失项视为已就绪。 */
    public Map<Long, IndexStatus> statuses(Collection<Long> pageIds) {
        Map<Long, IndexStatus> result = new HashMap<>();
        if (jdbc == null || pageIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", Collections.nCopies(pageIds.size(), "?"));
        // 按页倒序扫描，每页只取最近一次 UPSERT 任务的状态。
        jdbc.query("SELECT resource_id, state FROM indexing_job "
                        + "WHERE resource_type = 'PAGE' AND job_type = 'UPSERT' AND resource_id IN (" + placeholders + ") "
                        + "ORDER BY resource_id, updated_at DESC, id DESC",
                rs -> {
                    result.putIfAbsent(rs.getLong(1), toStatus(rs.getString(2)));
                },
                pageIds.toArray());
        return result;
    }

    private static IndexStatus toStatus(String state) {
        return switch (state) {
            case "PENDING", "LEASED", "RETRY_WAIT" -> IndexStatus.INDEXING;
            case "FAILED" -> IndexStatus.FAILED;
            default -> IndexStatus.READY;
        };
    }
}
