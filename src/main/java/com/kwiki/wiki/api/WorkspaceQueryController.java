package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 供智能体、共享空间与全局搜索导航页使用的真实数据源。 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("isAuthenticated()")
public class WorkspaceQueryController {
    private final JdbcOperations jdbc;
    private final ResourceAuthorizationService authorization;
    public WorkspaceQueryController(ObjectProvider<JdbcOperations> jdbc, ResourceAuthorizationService authorization) { this.jdbc = jdbc.getIfAvailable(); this.authorization = authorization; }

    @GetMapping("/agents")
    TransDTO<List<AgentView>> agents() { requireDb(); return TransDTO.success(jdbc.query("SELECT agent_key, name, description FROM agent_definition WHERE enabled = TRUE ORDER BY id", (rs, n) -> new AgentView(rs.getString(1), rs.getString(2), rs.getString(3)))); }

    @GetMapping("/agents/{agentKey}")
    TransDTO<AgentView> agent(@PathVariable String agentKey) {
        requireDb();
        return TransDTO.success(jdbc.queryForObject("SELECT agent_key, name, description FROM agent_definition WHERE agent_key = ? AND enabled = TRUE",
                (rs, n) -> new AgentView(rs.getString(1), rs.getString(2), rs.getString(3)), agentKey));
    }

    @GetMapping("/shared")
    TransDTO<List<PageView>> shared(@AuthenticationPrincipal CurrentUser user) {
        requireDb(); return TransDTO.success(jdbc.query("SELECT p.id, p.kb_id, p.title, p.updated_at FROM wiki_page p WHERE p.status = 'ACTIVE' ORDER BY p.updated_at DESC, p.id DESC LIMIT 200", (rs, n) -> new PageView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant())).stream().filter(page -> authorization.canRead(user, page.pageId())).toList());
    }

    @GetMapping("/search")
    TransDTO<List<PageView>> search(@AuthenticationPrincipal CurrentUser user, @RequestParam(defaultValue = "") String q) {
        requireDb(); String query = q.trim(); if (query.isEmpty()) return TransDTO.success(List.of());
        String escaped = query.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return TransDTO.success(jdbc.query("SELECT DISTINCT p.id, p.kb_id, p.title, p.updated_at FROM wiki_page p LEFT JOIN wiki_page_revision r ON r.id = p.current_published_revision_id WHERE p.status = 'ACTIVE' AND (LOWER(p.title) LIKE ? ESCAPE '\\\\' OR LOWER(r.plain_text) LIKE ? ESCAPE '\\\\') ORDER BY p.updated_at DESC, p.id DESC LIMIT 100", (rs, n) -> new PageView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()), "%" + escaped + "%", "%" + escaped + "%").stream().filter(page -> authorization.canRead(user, page.pageId())).toList());
    }

    @GetMapping("/recent-visits")
    TransDTO<List<PageView>> recentVisits(@AuthenticationPrincipal CurrentUser user) {
        requireDb(); return TransDTO.success(jdbc.query("SELECT p.id, p.kb_id, p.title, p.updated_at FROM recent_visit v JOIN wiki_page p ON p.id = v.page_id WHERE v.user_id = ? AND p.status = 'ACTIVE' ORDER BY v.visited_at DESC LIMIT 20", (rs, n) -> new PageView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()), user.id()).stream().filter(page -> authorization.canRead(user, page.pageId())).toList());
    }

    @GetMapping("/summary")
    TransDTO<SummaryView> summary(@AuthenticationPrincipal CurrentUser user) {
        requireDb();
        List<Long> visible = jdbc.query("SELECT id FROM wiki_page WHERE status = 'ACTIVE'", (rs, n) -> rs.getLong(1)).stream().filter(id -> authorization.canRead(user, id)).toList();
        List<SummaryItem> items = jdbc.query("SELECT s.page_id, p.title, s.summary_text FROM page_summary s JOIN wiki_page p ON p.id = s.page_id WHERE p.status = 'ACTIVE' ORDER BY s.updated_at DESC, s.page_id DESC LIMIT 100", (rs, n) -> new SummaryItem(rs.getLong(1), rs.getString(2), rs.getString(3))).stream().filter(item -> visible.contains(item.pageId())).toList();
        return TransDTO.success(new SummaryView(visible.size(), items));
    }

    private void requireDb() { if (jdbc == null) throw new IllegalStateException("database is unavailable"); }
    public record AgentView(String id, String title, String description) {}
    public record PageView(long pageId, long kbId, String title, Instant updatedAt) {}
    public record SummaryView(long count, List<SummaryItem> items) {}
    public record SummaryItem(long pageId, String title, String source) {}
}
