package com.kwiki.wiki.api;

import com.kwiki.wiki.domain.WikiLink;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.WikiLinkRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.render.InternalLinks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 页面到页面的链接会解析为稳定的页面 id。链接在发布时依据
 * 已发布的 Markdown 刷新；无法解析、跨知识库、已归档或
 * 自引用的目标会被跳过。反向链接在重命名与移动后依然存活，
 * 因为记录只引用页面 id。
 */
@Service
public class PageLinkService {

    private final WikiLinkRepository links;
    private final WikiPageRepository pages;
    private ResourceAuthorizationService resources;

    public PageLinkService(WikiLinkRepository links, WikiPageRepository pages) {
        this.links = links;
        this.pages = pages;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResources(ResourceAuthorizationService resources) { this.resources = resources; }

    /** 依据页面的已发布 Markdown 替换其出站链接集合。 */
    @Transactional
    public void refreshLinks(long pageId, long kbId, String markdown) {
        links.deleteAllBySourcePageId(pageId);
        Set<String> targetUuids = InternalLinks.extractPageUuids(markdown);
        for (String uuid : targetUuids) {
            Optional<WikiPage> target = pages.findByUuid(uuid)
                    .filter(page -> WikiPage.STATUS_ACTIVE.equals(page.getStatus()))
                    .filter(page -> page.getKbId() == kbId);
            target.ifPresent(resolved -> {
                if (resolved.getId() != pageId) {
                    links.save(new WikiLink(pageId, resolved.getId()));
                }
            });
        }
    }

    /** 链接到给定页面的那些页面；已归档的来源会被隐藏。 */
    public List<WikiPage> backlinks(long pageId) {
        List<WikiPage> result = new ArrayList<>();
        for (WikiLink link : links.findByTargetPageId(pageId)) {
            pages.findByIdAndStatus(link.getSourcePageId(), WikiPage.STATUS_ACTIVE)
                    .ifPresent(result::add);
        }
        return result;
    }

    /** 反向链接逐条过滤，因为共享目标页面绝不能
     * * 暴露来源页面的标题或 id。 */
    public List<WikiPage> backlinks(CurrentUser user, long pageId) {
        return backlinks(pageId).stream()
                .filter(page -> resources == null || resources.can(user, page.getId(), ResourceAction.READ))
                .toList();
    }
}
