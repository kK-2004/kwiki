package com.kwiki.infrastructure.mysql;

import com.kwiki.graph.GraphResourceId;
import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.persistence.GraphSourceEpochService;
import com.kwiki.indexing.parse.AttachmentIndexEligibility;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于现有授权与权威存储的全来源清单适配器。页面按 wiki_page 逐资源核验
 * （复用 ResourceAuthorizationService 的受众规则），可索引附件为已存储的
 * 图片且按知识库读权限核验；角色名或 kbId 可访问不能替代逐资源证明。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class JdbcGraphSourceInventory implements GraphSourceInventoryPort {

    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService resources;
    private final KnowledgeBaseAuthorizationService knowledgeBases;
    private final GraphSourceEpochService epochs;

    public JdbcGraphSourceInventory(JdbcOperations jdbc,
                                    ResourceAuthorizationService resources,
                                    KnowledgeBaseAuthorizationService knowledgeBases,
                                    GraphSourceEpochService epochs) {
        this.jdbc = jdbc;
        this.resources = resources;
        this.knowledgeBases = knowledgeBases;
        this.epochs = epochs;
    }

    /*
     * 下面的 jdbc.query(sql, rs -> {...}, args) 绑定的是 RowCallbackHandler：
     * JdbcTemplate 已经逐行调用 next() 再回调，回调里不能再写 while (rs.next())，
     * 否则每次查询都会丢掉第一行。
     */
    @Override
    public List<GraphResourceId> listCurrentSources(long kbId) {
        List<GraphResourceId> sources = new ArrayList<>();
        jdbc.query("SELECT id FROM wiki_page WHERE kb_id = ? AND status = 'ACTIVE'",
                rs -> {
                    sources.add(GraphResourceId.page(rs.getLong(1)));
                }, kbId);
        jdbc.query("SELECT id, content_type FROM attachment "
                        + "WHERE kb_id = ? AND status = 'STORED'",
                rs -> {
                    if (AttachmentIndexEligibility.isIndexableImage(rs.getString(2))) {
                        sources.add(GraphResourceId.attachment(rs.getLong(1)));
                    }
                }, kbId);
        sources.sort(Comparator.comparing(GraphResourceId::resourceType)
                .thenComparingLong(GraphResourceId::resourceId));
        return sources;
    }

    @Override
    public boolean canRead(long userId, boolean superuser, GraphResourceId resource) {
        CurrentUser user = new CurrentUser(userId, "graph-gate", superuser);
        if ("PAGE".equals(resource.resourceType())) {
            return resources.canRead(user, resource.resourceId());
        }
        if ("ATTACHMENT".equals(resource.resourceType())) {
            Long kbId = null;
            try {
                kbId = jdbc.queryForObject(
                        "SELECT kb_id FROM attachment WHERE id = ? AND status = 'STORED'",
                        Long.class, resource.resourceId());
            } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
                return false;
            }
            if (kbId == null) {
                return false;
            }
            return superuser || knowledgeBases.can(user, kbId, WikiAction.READ_PAGE);
        }
        return false;
    }

    @Override
    public long[] currentEpochs(long kbId) {
        return epochs.current(kbId);
    }

    /**
     * 批量生命周期判定（单条 SQL）：PAGE 以 ACTIVE 为 LIVE，ATTACHMENT 以 STORED
     * 为 LIVE；状态不匹配或行已不存在（含整库物理删除后）计为 RETIRED。
     * 一次图增强最多调用一次，供快照清单门禁与证据二次校验使用。
     */
    @Override
    public java.util.Map<GraphResourceId, com.kwiki.graph.GraphSourceInventoryPort.LifecycleState> lifecycleStates(
            List<GraphResourceId> resources) {
        java.util.Map<GraphResourceId, com.kwiki.graph.GraphSourceInventoryPort.LifecycleState> states =
                new java.util.LinkedHashMap<>();
        if (resources == null || resources.isEmpty()) {
            return states;
        }
        for (GraphResourceId resource : resources) {
            states.put(resource,
                    com.kwiki.graph.GraphSourceInventoryPort.LifecycleState.RETIRED);
        }
        List<Long> pageIds = resources.stream()
                .filter(resource -> "PAGE".equals(resource.resourceType()))
                .map(GraphResourceId::resourceId)
                .toList();
        List<Long> attachmentIds = resources.stream()
                .filter(resource -> "ATTACHMENT".equals(resource.resourceType()))
                .map(GraphResourceId::resourceId)
                .toList();
        if (!pageIds.isEmpty()) {
            jdbc.query("SELECT id FROM wiki_page WHERE id IN (" + placeholders(pageIds.size())
                            + ") AND status = 'ACTIVE'",
                    rs -> {
                        states.put(GraphResourceId.page(rs.getLong(1)),
                                com.kwiki.graph.GraphSourceInventoryPort.LifecycleState.LIVE);
                    }, pageIds.toArray());
        }
        if (!attachmentIds.isEmpty()) {
            jdbc.query("SELECT id FROM attachment WHERE id IN (" + placeholders(attachmentIds.size())
                            + ") AND status = 'STORED'",
                    rs -> {
                        states.put(GraphResourceId.attachment(rs.getLong(1)),
                                com.kwiki.graph.GraphSourceInventoryPort.LifecycleState.LIVE);
                    }, attachmentIds.toArray());
        }
        return states;
    }

    /** 资源行是否仍存在于业务库（任何生命周期状态，含 ARCHIVED）。 */
    @Override
    public boolean exists(GraphResourceId resource) {
        if ("PAGE".equals(resource.resourceType())) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wiki_page WHERE id = ?", Integer.class,
                    resource.resourceId());
            return count != null && count > 0;
        }
        if ("ATTACHMENT".equals(resource.resourceType())) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attachment WHERE id = ?", Integer.class,
                    resource.resourceId());
            return count != null && count > 0;
        }
        return false;
    }

    /** 批量存在性判定：按 PAGE / ATTACHMENT 分组，每组每 500 个一条 SQL。 */
    @Override
    public java.util.Set<GraphResourceId> existingResources(List<GraphResourceId> resources) {
        java.util.Set<GraphResourceId> existing = new java.util.LinkedHashSet<>();
        if (resources == null || resources.isEmpty()) {
            return existing;
        }
        collectExisting(resources, "PAGE", "wiki_page", GraphResourceId::page, existing);
        collectExisting(resources, "ATTACHMENT", "attachment", GraphResourceId::attachment, existing);
        return existing;
    }

    private void collectExisting(List<GraphResourceId> resources, String resourceType, String table,
                                 java.util.function.LongFunction<GraphResourceId> identity,
                                 java.util.Set<GraphResourceId> existing) {
        List<Long> ids = resources.stream()
                .filter(resource -> resourceType.equals(resource.resourceType()))
                .map(GraphResourceId::resourceId)
                .distinct()
                .toList();
        for (int from = 0; from < ids.size(); from += EXISTS_BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + EXISTS_BATCH));
            jdbc.query("SELECT id FROM " + table + " WHERE id IN (" + placeholders(batch.size()) + ")",
                    rs -> {
                        existing.add(identity.apply(rs.getLong(1)));
                    }, batch.toArray());
        }
    }

    private static final int EXISTS_BATCH = 500;

    private static String placeholders(int count) {
        StringBuilder out = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('?');
        }
        return out.toString();
    }
}
