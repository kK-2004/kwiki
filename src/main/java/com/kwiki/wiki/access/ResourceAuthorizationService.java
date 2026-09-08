package com.kwiki.wiki.access;

import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Server-side document boundary. A knowledge-base role is only one input: a
 * private/selected page can narrow it, while a page owner or page administrator
 * can manage that page without widening access to sibling pages.
 */
@Service
public class ResourceAuthorizationService {

    private final JdbcOperations jdbc;
    private final KnowledgeBaseAuthorizationService knowledgeBases;

    public ResourceAuthorizationService(ObjectProvider<JdbcOperations> jdbc,
                                        KnowledgeBaseAuthorizationService knowledgeBases) {
        this.jdbc = jdbc.getIfAvailable();
        this.knowledgeBases = knowledgeBases;
    }

    public boolean canRead(CurrentUser user, long pageId) {
        return can(user, pageId, ResourceAction.READ);
    }

    public boolean can(CurrentUser user, long pageId, ResourceAction action) {
        if (user == null || jdbc == null) return false;
        Map<String, Object> page;
        try {
            page = jdbc.queryForMap(
                    "SELECT kb_id, owner_id, audience_mode FROM wiki_page "
                            + "WHERE id = ? AND status = 'ACTIVE'", pageId);
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
        long kbId = number(page.get("kb_id"));
        long ownerId = number(page.get("owner_id"));
        String audience = String.valueOf(page.getOrDefault("audience_mode", "KB_MEMBERS"));
        if (user.admin() || user.id() == ownerId) return true;

        String directRole = jdbc.query(
                "SELECT role FROM wiki_page_member WHERE page_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, pageId, user.id());
        if (directRole != null && allows(directRole, action)) return true;
        if (action == ResourceAction.MANAGE
                && knowledgeBases.can(user, kbId, WikiAction.MANAGE_MEMBERS)) return true;
        if (action == ResourceAction.TRANSFER) return false;
        if (knowledgeBases.can(user, kbId, WikiAction.MANAGE_MEMBERS)) return true;

        if ("SELECTED_MEMBERS".equals(audience)) {
            Integer selected = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wiki_page_audience_member a "
                            + "JOIN knowledge_base_member m ON m.kb_id = a.source_kb_id "
                            + "AND m.user_id = a.user_id "
                            + "WHERE a.page_id = ? AND a.user_id = ?", Integer.class, pageId, user.id());
            if (selected != null && selected > 0) return true;
        } else if ("KB_MEMBERS".equals(audience)
                && knowledgeBases.can(user, kbId, WikiAction.READ_PAGE)) {
            return true;
        }
        return false;
    }

    public void require(CurrentUser user, long pageId, ResourceAction action) {
        if (!can(user, pageId, action)) throw new AccessDeniedException("resource access denied");
    }

    public void requireInKnowledgeBase(CurrentUser user, long kbId, long pageId, ResourceAction action) {
        if (jdbc == null) throw new AccessDeniedException("resource access denied");
        Long actual = jdbc.queryForObject("SELECT kb_id FROM wiki_page WHERE id = ? AND status = 'ACTIVE'", Long.class, pageId);
        if (actual == null || actual != kbId) throw new AccessDeniedException("resource access denied");
        require(user, pageId, action);
    }

    private boolean allows(String role, ResourceAction action) {
        return switch (action) {
            case READ -> true;
            case EDIT -> "EDITOR".equals(role) || "ADMIN".equals(role);
            case MANAGE -> "ADMIN".equals(role);
            case TRANSFER -> false;
        };
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
