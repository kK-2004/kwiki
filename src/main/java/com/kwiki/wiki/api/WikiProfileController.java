package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 用户个人主页上真实的、按权限过滤的 Wiki 互动数据。 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("isAuthenticated()")
public class WikiProfileController {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;

    public WikiProfileController(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization) { this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization; }

    @GetMapping({"/me/likes", "/{userId}/likes"})
    TransDTO<List<ProfilePage>> likes(@AuthenticationPrincipal CurrentUser user, @PathVariable(required = false) Long userId) { return TransDTO.success(list(user, userId == null ? user.id() : userId, true)); }

    @GetMapping({"/me/favorites", "/{userId}/favorites"})
    TransDTO<List<ProfilePage>> favorites(@AuthenticationPrincipal CurrentUser user, @PathVariable(required = false) Long userId) { return TransDTO.success(list(user, userId == null ? user.id() : userId, false)); }

    private List<ProfilePage> list(CurrentUser viewer, long target, boolean likes) {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
        String table = likes ? "page_like" : "page_favorite";
        return jdbc.query("SELECT p.id, p.kb_id, p.title, p.updated_at FROM " + table + " r JOIN wiki_page p ON p.id = r.page_id WHERE r.user_id = ? AND p.status = 'ACTIVE' ORDER BY r.created_at DESC, p.id DESC",
                (rs, n) -> new ProfilePage(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()), target).stream()
                .filter(page -> authorization.canRead(viewer, page.pageId())).toList();
    }

    public record ProfilePage(long pageId, long kbId, String title, Instant updatedAt) {}
}
