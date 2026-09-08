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
 * Page-to-page links resolved to stable page ids. Links refresh on publish from
 * the published Markdown; unresolved, cross-knowledge-base, archived, or
 * self-referencing targets are skipped. Backlinks survive renames and moves
 * because rows reference page ids only.
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

    /** Replaces the outgoing link set of a page from its published Markdown. */
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

    /** Pages that link to the given page; archived sources are hidden. */
    public List<WikiPage> backlinks(long pageId) {
        List<WikiPage> result = new ArrayList<>();
        for (WikiLink link : links.findByTargetPageId(pageId)) {
            pages.findByIdAndStatus(link.getSourcePageId(), WikiPage.STATUS_ACTIVE)
                    .ifPresent(result::add);
        }
        return result;
    }

    /** Backlinks are filtered one by one because sharing the target page must not
     * expose the source page's title or id. */
    public List<WikiPage> backlinks(CurrentUser user, long pageId) {
        return backlinks(pageId).stream()
                .filter(page -> resources == null || resources.can(user, page.getId(), ResourceAction.READ))
                .toList();
    }
}
