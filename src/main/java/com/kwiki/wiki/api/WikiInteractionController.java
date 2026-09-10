package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 页面点赞、收藏、统计以及根/回复评论 API。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}")
@PreAuthorize("isAuthenticated()")
public class WikiInteractionController {

    private final WikiInteractionService interactions;
    private final ResourceAuthorizationService authorization;

    public WikiInteractionController(WikiInteractionService interactions, ResourceAuthorizationService authorization) {
        this.interactions = interactions; this.authorization = authorization;
    }

    public record CommentRequest(@NotBlank String body, Long replyTo, Long anchorId,
                                  List<Long> mentionUserIds) {}

    @GetMapping("/statistics")
    TransDTO<WikiInteractionService.InteractionState> statistics(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.interactionState(user, pageId));
    }

    @PutMapping("/likes")
    TransDTO<WikiInteractionService.InteractionState> like(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.setPageLike(user, pageId, true));
    }

    @DeleteMapping("/likes")
    TransDTO<WikiInteractionService.InteractionState> unlike(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.setPageLike(user, pageId, false));
    }

    @PutMapping("/favorites")
    TransDTO<WikiInteractionService.InteractionState> favorite(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.setFavorite(user, pageId, true));
    }

    @DeleteMapping("/favorites")
    TransDTO<WikiInteractionService.InteractionState> unfavorite(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.setFavorite(user, pageId, false));
    }

    @GetMapping("/comments")
    TransDTO<List<WikiInteractionService.CommentView>> comments(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.comments(user, pageId));
    }

    @GetMapping("/comments/roots")
    TransDTO<WikiInteractionService.CommentPage> roots(@AuthenticationPrincipal CurrentUser user,
                                                       @PathVariable long kbId, @PathVariable long pageId,
                                                       @RequestParam(defaultValue = "0") long afterId,
                                                       @RequestParam(defaultValue = "50") int limit) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.rootComments(user, pageId, afterId, limit));
    }

    @GetMapping("/comments/roots/{rootId}/replies")
    TransDTO<WikiInteractionService.CommentPage> replies(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable long kbId, @PathVariable long pageId,
                                                         @PathVariable long rootId,
                                                         @RequestParam(defaultValue = "0") long afterId,
                                                         @RequestParam(defaultValue = "50") int limit) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.replies(user, pageId, rootId, afterId, limit));
    }

    @PostMapping("/comments")
    TransDTO<WikiInteractionService.CommentView> comment(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long kbId,
            @PathVariable long pageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentRequest request) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(interactions.addComment(user, pageId, request.body(), request.replyTo(), request.anchorId(), request.mentionUserIds(), idempotencyKey));
    }

    @DeleteMapping("/comments/{commentId}")
    TransDTO<Void> deleteComment(@AuthenticationPrincipal CurrentUser user, @PathVariable long commentId) {
        interactions.deleteComment(user, commentId);
        return TransDTO.success();
    }

    @PutMapping("/comments/{commentId}/likes")
    TransDTO<WikiInteractionService.InteractionState> likeComment(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long commentId) {
        return TransDTO.success(interactions.setCommentLike(user, commentId, true));
    }

    @DeleteMapping("/comments/{commentId}/likes")
    TransDTO<WikiInteractionService.InteractionState> unlikeComment(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long commentId) {
        return TransDTO.success(interactions.setCommentLike(user, commentId, false));
    }
}
