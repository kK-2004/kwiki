package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}/members")
@PreAuthorize("isAuthenticated()")
public class PageCollaborationController {
    private final PageCollaborationService members;
    public PageCollaborationController(PageCollaborationService members) { this.members = members; }
    public record UpsertRequest(@NotNull Long userId, @NotBlank String role) {}

    @GetMapping
    TransDTO<List<PageCollaborationService.MemberView>> list(@AuthenticationPrincipal CurrentUser user,
                                                             @PathVariable long kbId, @PathVariable long pageId) {
        return TransDTO.success(members.list(user, kbId, pageId));
    }

    @PutMapping
    TransDTO<PageCollaborationService.MemberView> upsert(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable long kbId, @PathVariable long pageId,
                                                         @Valid @RequestBody UpsertRequest request) {
        return TransDTO.success(members.upsert(user, kbId, pageId, request.userId(), request.role()));
    }

    @DeleteMapping("/{userId}")
    TransDTO<Void> remove(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId,
                          @PathVariable long pageId, @PathVariable long userId) {
        members.remove(user, kbId, pageId, userId); return TransDTO.success();
    }
}
