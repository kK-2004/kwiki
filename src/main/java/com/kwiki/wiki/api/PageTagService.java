package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageTag;
import com.kwiki.wiki.domain.WikiTag;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageTagRepository;
import com.kwiki.wiki.persistence.WikiTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Knowledge-base-scoped tags attached to pages; setting replaces the page's set. */
@Service
public class PageTagService {

    private final WikiTagRepository tags;
    private final WikiPageTagRepository pageTags;
    private final WikiPageRepository pages;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;

    public PageTagService(WikiTagRepository tags, WikiPageTagRepository pageTags,
                          WikiPageRepository pages,
                          KnowledgeBaseAuthorizationService authorization) {
        this(tags, pageTags, pages, authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageTagService(WikiTagRepository tags, WikiPageTagRepository pageTags,
                          WikiPageRepository pages,
                          KnowledgeBaseAuthorizationService authorization,
                          ResourceAuthorizationService resources) {
        this.tags = tags;
        this.pageTags = pageTags;
        this.pages = pages;
        this.authorization = authorization;
        this.resources = resources;
    }

    @Transactional
    public List<String> setTags(CurrentUser user, long kbId, long pageId, List<String> names) {
        requirePage(user, kbId, pageId, ResourceAction.EDIT, WikiAction.EDIT_PAGE);
        requireActivePageIn(kbId, pageId);

        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }

        pageTags.deleteAllByPageId(pageId);
        for (String name : normalized) {
            WikiTag tag = tags.findByKbIdAndNameIgnoreCase(kbId, name)
                    .orElseGet(() -> tags.save(new WikiTag(kbId, name)));
            pageTags.save(new WikiPageTag(pageId, tag.getId()));
        }
        return List.copyOf(normalized);
    }

    public List<String> listTags(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId, ResourceAction.READ, WikiAction.READ_PAGE);
        requireActivePageIn(kbId, pageId);
        return pageTags.findByPageId(pageId).stream()
                .map(pageTag -> tags.findById(pageTag.getTagId())
                        .map(WikiTag::getName)
                        .orElse(""))
                .filter(name -> !name.isEmpty())
                .sorted()
                .toList();
    }

    private void requireActivePageIn(long kbId, long pageId) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("page not found"));
        if (page.getKbId() != kbId) {
            throw new NotFoundException("page not found");
        }
    }

    private void requirePage(CurrentUser user, long kbId, long pageId,
                             ResourceAction action, WikiAction fallback) {
        if (resources != null) resources.requireInKnowledgeBase(user, kbId, pageId, action);
        else authorization.require(user, kbId, fallback);
    }
}
