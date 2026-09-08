package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseRole;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@PreAuthorize("isAuthenticated()")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBases;
    private final com.kwiki.wiki.persistence.AppUserRepository users;
    private final KnowledgeBaseCollaborationSettingsService collaborationSettings;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases,
                                   KnowledgeBaseCollaborationSettingsService collaborationSettings,
                                   com.kwiki.wiki.persistence.AppUserRepository users) {
        this.users = users;
        this.knowledgeBases = knowledgeBases;
        this.collaborationSettings = collaborationSettings;
    }

    public record CreateKnowledgeBaseRequest(
            @NotBlank String name,
            String description) {
    }

    public record UpdateKnowledgeBaseRequest(
            @NotBlank String name,
            String description) {
    }

    public record MemberUpsertRequest(
            @NotNull Long userId,
            @NotNull KnowledgeBaseRole role) {
    }

    public record KnowledgeBaseView(
            Long id, String uuid, String name, String description, String status,
            boolean canManage, boolean canUpload, boolean canEditSettings, boolean canTransfer) {
    }

    public record MemberView(Long userId, KnowledgeBaseRole role, String username, String displayName) {
    }

    @PostMapping
    TransDTO<KnowledgeBaseView> create(@AuthenticationPrincipal CurrentUser user,
                                       @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return TransDTO.success(toView(user, knowledgeBases.create(user, request.name(), request.description())));
    }

    @GetMapping
    TransDTO<List<KnowledgeBaseView>> list(@AuthenticationPrincipal CurrentUser user) {
        return TransDTO.success(knowledgeBases.listAccessible(user).stream().map(kb -> toView(user, kb)).toList());
    }

    @GetMapping("/{kbId}")
    TransDTO<KnowledgeBaseView> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId) {
        return TransDTO.success(toView(user, knowledgeBases.requireAccessible(user, kbId)));
    }

    @PutMapping("/{kbId}")
    TransDTO<KnowledgeBaseView> update(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId,
                                     @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return TransDTO.success(toView(user, knowledgeBases.update(user, kbId, request.name().trim(), request.description())));
    }

    private MemberView memberView(KnowledgeBaseMember member) {
        var account = users.findById(member.getUserId());
        return new MemberView(member.getUserId(), member.getRole(),
                account.map(com.kwiki.wiki.domain.AppUser::getUsername).orElse("已停用用户"),
                account.map(com.kwiki.wiki.domain.AppUser::getDisplayName).orElse(""));
    }

    @PostMapping("/{kbId}/archive")
    TransDTO<Void> archive(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId) {
        knowledgeBases.archive(user, kbId);
        return TransDTO.success();
    }

    @GetMapping("/{kbId}/members")
    TransDTO<List<MemberView>> members(@AuthenticationPrincipal CurrentUser user,
                                       @PathVariable long kbId) {
        return TransDTO.success(knowledgeBases.listMembers(user, kbId).stream()
                .map(this::memberView)
                .toList());
    }

    @PutMapping("/{kbId}/members")
    TransDTO<MemberView> upsertMember(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable long kbId,
                                      @Valid @RequestBody MemberUpsertRequest request) {
        if (!knowledgeBases.canManage(user, kbId)) throw new org.springframework.security.access.AccessDeniedException("forbidden");
        if (users.findById(request.userId()).filter(com.kwiki.wiki.domain.AppUser::isActive).isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        KnowledgeBaseMember saved = knowledgeBases.upsertMember(
                user, kbId, request.userId(), request.role());
        return TransDTO.success(memberView(saved));
    }

    @DeleteMapping("/{kbId}/members/{userId}")
    TransDTO<Void> removeMember(@AuthenticationPrincipal CurrentUser user,
                                @PathVariable long kbId,
                                @PathVariable long userId) {
        knowledgeBases.removeMember(user, kbId, userId);
        return TransDTO.success();
    }

    public record CollaborationSettingsRequest(boolean joinApprovalRequired) {}

    @GetMapping("/{kbId}/collaboration-settings")
    TransDTO<KnowledgeBaseCollaborationSettingsService.Settings> collaborationSettings(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId) {
        return TransDTO.success(collaborationSettings.get(user, kbId));
    }

    @PutMapping("/{kbId}/collaboration-settings")
    TransDTO<KnowledgeBaseCollaborationSettingsService.Settings> updateCollaborationSettings(
            @AuthenticationPrincipal CurrentUser user, @PathVariable long kbId,
            @Valid @RequestBody CollaborationSettingsRequest request) {
        return TransDTO.success(collaborationSettings.update(user, kbId, request.joinApprovalRequired()));
    }

    private KnowledgeBaseView toView(CurrentUser user, KnowledgeBase kb) {
        return new KnowledgeBaseView(kb.getId(), kb.getUuid(), kb.getName(),
                kb.getDescription(), kb.getStatus(), knowledgeBases.canManage(user, kb.getId()),
                knowledgeBases.canUpload(user, kb.getId()), user.admin() || user.id() == kb.getOwnerId(), user.id() == kb.getOwnerId());
    }
}
