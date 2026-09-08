package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resource invitation approval switch. Page invitations inherit their KB switch. */
@Service
public class KnowledgeBaseCollaborationSettingsService {
    private final JdbcOperations jdbc;
    private final KnowledgeBaseAuthorizationService authorization;

    public KnowledgeBaseCollaborationSettingsService(ObjectProvider<JdbcOperations> jdbc,
                                                     KnowledgeBaseAuthorizationService authorization) {
        this.jdbc = jdbc.getIfAvailable();
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public Settings get(CurrentUser user, long kbId) {
        requireDb();
        authorization.require(user, kbId, WikiAction.READ_PAGE);
        Boolean value = jdbc.queryForObject("SELECT join_approval_required FROM knowledge_base WHERE id = ? AND status = 'ACTIVE'", Boolean.class, kbId);
        return new Settings(Boolean.TRUE.equals(value));
    }

    @Transactional
    public Settings update(CurrentUser user, long kbId, boolean required) {
        requireDb();
        authorization.require(user, kbId, WikiAction.MANAGE_MEMBERS);
        jdbc.update("UPDATE knowledge_base SET join_approval_required = ? WHERE id = ? AND status = 'ACTIVE'", required, kbId);
        return new Settings(required);
    }

    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    public record Settings(boolean joinApprovalRequired) {}
}
