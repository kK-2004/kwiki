package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
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
 * 有序的层级操作。该服务在触碰仓储之前会拒绝环
 * （把节点移动到自己的后代之下）以及跨知识库的移动；
 * 标识符在移动与重命名后保持稳定。
 */
@Service
public class WikiTreeService {

    private final WikiPageRepository pages;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;
    private final com.kwiki.wiki.archive.ResourceArchiveService archiveService;

    public WikiTreeService(WikiPageRepository pages,
                           KnowledgeBaseAuthorizationService authorization) {
        this(pages, authorization, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WikiTreeService(WikiPageRepository pages,
                           KnowledgeBaseAuthorizationService authorization,
                           ResourceAuthorizationService resources,
                           com.kwiki.wiki.archive.ResourceArchiveService archiveService) {
        this.pages = pages;
        this.authorization = authorization;
        this.resources = resources;
        this.archiveService = archiveService;
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

    /** 委托给统一的回收站归档服务（子树批次）。 */
    public void archive(CurrentUser user, long kbId, long pageId) {
        if (archiveService != null) {
            archiveService.archivePage(user, kbId, pageId);
            return;
        }
        authorization.require(user, kbId, WikiAction.ARCHIVE_PAGE);
        WikiPage page = requireActivePageIn(kbId, pageId);
        page.archive();
        pages.save(page);
    }

    /** 供导航使用的 ACTIVE 页面嵌套树，按 sibling_order 排序。 */
    public List<TreeNodeView> tree(CurrentUser user, long kbId) {
        List<WikiPage> ordered = pages
                .findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(kbId, WikiPage.STATUS_ACTIVE);
        if (resources != null) {
            ordered = ordered.stream()
                    .filter(page -> resources.can(user, page.getId(), ResourceAction.READ))
                    .toList();
            // 被共享到某个页面的用户即使不是知识库成员，
            // 也可以浏览该页面。返回只包含可读节点的树；
            // 对完全不可读/非成员的工作空间直接拒绝，
            // 且不暴露父节点元数据。
            if (ordered.isEmpty() && !authorization.can(user, kbId, WikiAction.READ_PAGE)) {
                throw new org.springframework.security.access.AccessDeniedException("access denied");
            }
        } else {
            authorization.require(user, kbId, WikiAction.READ_PAGE);
        }
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

    /** 当 {@code candidate} 位于 {@code page} 的祖先链上时返回 true。 */
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
