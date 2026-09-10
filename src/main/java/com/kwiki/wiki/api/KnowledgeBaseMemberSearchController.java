package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 仅管理员可用、有界上限的账号查询，用于添加协作者。绝不暴露邮箱或凭据。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/member-candidates")
@PreAuthorize("isAuthenticated()")
public class KnowledgeBaseMemberSearchController {
    private final JdbcOperations jdbc;
    private final KnowledgeBaseAuthorizationService authorization;
    public KnowledgeBaseMemberSearchController(org.springframework.beans.factory.ObjectProvider<JdbcOperations> jdbc, KnowledgeBaseAuthorizationService authorization) {
        this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization;
    }
    @GetMapping
    TransDTO<List<Candidate>> search(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId,
                                   @RequestParam(defaultValue = "") String q) {
        authorization.require(user, kbId, WikiAction.MANAGE_MEMBERS);
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        String query = q.trim();
        if (query.length() < 2) return TransDTO.success(List.of());
        return TransDTO.success(jdbc.query("SELECT id, username, display_name FROM app_user WHERE is_active = TRUE "
                + "AND (LOCATE(LOWER(?), LOWER(username)) > 0 OR LOCATE(LOWER(?), LOWER(display_name)) > 0) "
                + "ORDER BY username LIMIT 20", (rs, n) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)), query, query));
    }
    public record Candidate(long userId, String username, String displayName) {}
}
