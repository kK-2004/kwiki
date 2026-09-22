package com.kwiki.infrastructure.mysql;

import com.kwiki.graph.GraphResourceId;
import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.persistence.GraphSourceEpochService;
import com.kwiki.indexing.parse.AttachmentIndexEligibility;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@ConditionalOnBean(JdbcOperations.class)
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

    @Override
    public List<GraphResourceId> listCurrentSources(long kbId) {
        List<GraphResourceId> sources = new ArrayList<>();
        jdbc.query("SELECT id FROM wiki_page WHERE kb_id = ? AND status = 'ACTIVE'",
                rs -> {
                    while (rs.next()) {
                        sources.add(GraphResourceId.page(rs.getLong(1)));
                    }
                }, kbId);
        jdbc.query("SELECT id, content_type FROM attachment "
                        + "WHERE kb_id = ? AND status = 'STORED'",
                rs -> {
                    while (rs.next()) {
                        if (AttachmentIndexEligibility.isIndexableImage(rs.getString(2))) {
                            sources.add(GraphResourceId.attachment(rs.getLong(1)));
                        }
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
}
