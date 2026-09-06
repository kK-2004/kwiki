package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ordered hierarchy operations. The service rejects cycles (a node moved beneath
 * its own descendant) and cross-knowledge-base moves before touching the
 * repository; identifiers stay stable across moves and renames.
 */
@Service
public class WikiTreeService {

    private final WikiPageRepository pages;
    private final KnowledgeBaseAuthorizationService authorization;

    public WikiTreeService(WikiPageRepository pages,
                           KnowledgeBaseAuthorizationService authorization) {
        this.pages = pages;
        this.authorization = authorization;
    }

    @Transactional
    public WikiPage createNode(CurrentUser user, long kbId, Long parentId, String title,
                               String nodeType, Integer position) {
        authorization.require(user, kbId, WikiAction.CREATE_PAGE);
        WikiPage parent = null;
        if (parentId != null) {
            parent = pages.findByIdAndStatus(parentId, WikiPage.STATUS_ACTIVE)
                    .orElseThrow(() -> new NotFoundException("parent not found"));
            if (parent.getKbId() != kbId) {
                throw new IllegalArgumentException("parent belongs to another knowledge base");
            }
        }
        int order = resolveOrder(kbId, parentId, position);
        return pages.save(new WikiPage(UUID.randomUUID().toString(), kbId, parentId,
                title, nodeType, order, user.id()));
    }

    @Transactional
    public WikiPage move(CurrentUser user, long kbId, long pageId, Long newParentId,
                         Integer position) {
        authorization.require(user, kbId, WikiAction.EDIT_PAGE);
        WikiPage page = requireActivePageIn(kbId, pageId);
        if (newParentId != null) {
            if (newParentId == pageId) {
                throw new IllegalArgumentException("a page cannot be its own parent");
            }
            WikiPage newParent = pages.findByIdAndStatus(newParentId, WikiPage.STATUS_ACTIVE)
                    .orElseThrow(() -> new NotFoundException("target parent not found"));
            if (newParent.getKbId() != kbId) {
                throw new IllegalArgumentException("target parent belongs to another knowledge base");
            }
            if (isAncestorOf(page, newParent)) {
                throw new IllegalArgumentException("cannot move a page beneath its own descendant");
            }
        }
        page.moveTo(newParentId, resolveOrder(kbId, newParentId, position));
        return pages.save(page);
    }

    @Transactional
    public void archive(CurrentUser user, long kbId, long pageId) {
        authorization.require(user, kbId, WikiAction.ARCHIVE_PAGE);
        WikiPage page = requireActivePageIn(kbId, pageId);
        page.archive();
        pages.save(page);
    }

    /** Nested tree of ACTIVE pages for navigation, ordered by sibling_order. */
    public List<TreeNodeView> tree(CurrentUser user, long kbId) {
        authorization.require(user, kbId, WikiAction.READ_PAGE);
        List<WikiPage> ordered = pages
                .findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(kbId, WikiPage.STATUS_ACTIVE);
        return buildTree(ordered);
    }

    private WikiPage requireActivePageIn(long kbId, long pageId) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("page not found"));
        if (page.getKbId() != kbId) {
            throw new NotFoundException("page not found");
        }
        return page;
    }

    /** True when {@code candidate} lies on the ancestor chain of {@code page}. */
    private boolean isAncestorOf(WikiPage candidate, WikiPage page) {
        Long parentId = page.getParentId();
        while (parentId != null) {
            if (parentId.equals(candidate.getId())) {
                return true;
            }
            parentId = pages.findById(parentId)
                    .map(WikiPage::getParentId)
                    .orElse(null);
        }
        return false;
    }

    private int resolveOrder(long kbId, Long parentId, Integer position) {
        List<WikiPage> siblings =
                pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(
                        kbId, parentId, WikiPage.STATUS_ACTIVE);
        if (position == null || position < 0 || position > siblings.size()) {
            return siblings.size();
        }
        return position;
    }

    static List<TreeNodeView> buildTree(List<WikiPage> orderedPages) {
        Map<Long, TreeNodeView> views = new HashMap<>();
        for (WikiPage page : orderedPages) {
            views.put(page.getId(), new TreeNodeView(page.getId(), page.getUuid(),
                    page.getTitle(), page.getNodeType(), new ArrayList<>()));
        }
        List<TreeNodeView> roots = new ArrayList<>();
        for (WikiPage page : orderedPages) {
            TreeNodeView view = views.get(page.getId());
            TreeNodeView parent = page.getParentId() == null ? null : views.get(page.getParentId());
            if (parent == null) {
                roots.add(view);
            } else {
                parent.children().add(view);
            }
        }
        Comparator<TreeNodeView> byOrder = Comparator.comparingInt(view ->
                orderedPages.stream()
                        .filter(p -> p.getId().equals(view.id()))
                        .findFirst()
                        .map(WikiPage::getSiblingOrder)
                        .orElse(0));
        sortRecursively(roots, byOrder);
        return roots;
    }

    private static void sortRecursively(List<TreeNodeView> nodes, Comparator<TreeNodeView> byOrder) {
        nodes.sort(byOrder);
        for (TreeNodeView node : nodes) {
            sortRecursively(node.children(), byOrder);
        }
    }

    public record TreeNodeView(Long id, String uuid, String title, String nodeType,
                               List<TreeNodeView> children) {
    }
}
