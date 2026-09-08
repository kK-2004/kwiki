package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}")
@PreAuthorize("isAuthenticated()")
public class RecentVisitController {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    public RecentVisitController(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization) { this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization; }
    @PostMapping("/visit")
    TransDTO<Void> visit(@AuthenticationPrincipal CurrentUser user, @PathVariable long pageId) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        authorization.require(user, pageId, ResourceAction.READ);
        jdbc.update("INSERT INTO recent_visit (user_id, page_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE visited_at = CURRENT_TIMESTAMP(6)", user.id(), pageId);
        return TransDTO.success();
    }
}
