package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.WikiPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}")
@PreAuthorize("isAuthenticated()")
public class WikiTreeController {

    private final WikiTreeService tree;

    public WikiTreeController(WikiTreeService tree) {
        this.tree = tree;
    }

    public record CreateNodeRequest(
            Long parentId,
            @NotBlank String title,
            String nodeType,
            Integer position) {
    }

    public record MoveNodeRequest(Long parentId, Integer position) {
    }

    public record NodeView(Long id, String uuid, String title, String nodeType,
                           Long parentId, int siblingOrder) {
    }

    @GetMapping("/tree")
    TransDTO<List<WikiTreeService.TreeNodeView>> tree(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable long kbId) {
        return TransDTO.success(tree.tree(user, kbId));
    }

    @PostMapping("/nodes")
    TransDTO<NodeView> create(@AuthenticationPrincipal CurrentUser user,
                              @PathVariable long kbId,
                              @Valid @RequestBody CreateNodeRequest request) {
        String type = request.nodeType() == null ? WikiPage.TYPE_PAGE : request.nodeType();
        WikiPage created = tree.createNode(user, kbId, request.parentId(),
                request.title(), type, request.position());
        return TransDTO.success(toView(created));
    }

    @PostMapping("/nodes/{pageId}/move")
    TransDTO<NodeView> move(@AuthenticationPrincipal CurrentUser user,
                            @PathVariable long kbId,
                            @PathVariable long pageId,
                            @RequestBody MoveNodeRequest request) {
        return TransDTO.success(toView(tree.move(user, kbId, pageId, request.parentId(), request.position())));
    }

    @PostMapping("/nodes/{pageId}/archive")
    TransDTO<Void> archive(@AuthenticationPrincipal CurrentUser user,
                           @PathVariable long kbId,
                           @PathVariable long pageId) {
        tree.archive(user, kbId, pageId);
        return TransDTO.success();
    }

    private NodeView toView(WikiPage page) {
        return new NodeView(page.getId(), page.getUuid(), page.getTitle(), page.getNodeType(),
                page.getParentId(), page.getSiblingOrder());
    }
}
