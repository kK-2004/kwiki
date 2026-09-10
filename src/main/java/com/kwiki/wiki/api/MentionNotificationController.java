package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 受权限约束的 @ 候选人与当前认证用户的消息中心。 */
@RestController
@PreAuthorize("isAuthenticated()")
public class MentionNotificationController {

    private final MentionNotificationService service;
    private final ResourceAuthorizationService authorization;

    public MentionNotificationController(MentionNotificationService service, ResourceAuthorizationService authorization) {
        this.service = service; this.authorization = authorization;
    }

    @GetMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}/mention-candidates")
    TransDTO<List<MentionNotificationService.UserCandidate>> candidates(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long kbId,
            @PathVariable long pageId,
            @RequestParam(defaultValue = "") @Size(max = 64) String q,
            @RequestParam(defaultValue = "20") @Max(50) int limit) {
        authorization.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        return TransDTO.success(service.candidates(user, pageId, q, limit));
    }

    @GetMapping("/api/v1/notifications")
    TransDTO<List<MentionNotificationService.NotificationView>> notifications(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue = "30") @Max(100) int limit) {
        return TransDTO.success(service.notifications(user, limit));
    }

    @GetMapping("/api/v1/notifications/unread-count")
    TransDTO<Long> unreadCount(@AuthenticationPrincipal CurrentUser user) {
        return TransDTO.success(service.unreadCount(user));
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    TransDTO<Void> markRead(@AuthenticationPrincipal CurrentUser user, @PathVariable long notificationId) {
        service.markRead(user, notificationId);
        return TransDTO.success();
    }

    @PostMapping("/api/v1/notifications/read-all")
    TransDTO<Void> markAllRead(@AuthenticationPrincipal CurrentUser user) {
        service.markAllRead(user);
        return TransDTO.success();
    }
}
