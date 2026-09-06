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

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
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
            Long id, String uuid, String name, String description, String status) {
    }

    public record MemberView(Long userId, KnowledgeBaseRole role) {
    }

    @PostMapping
    TransDTO<KnowledgeBaseView> create(@AuthenticationPrincipal CurrentUser user,
                                       @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return TransDTO.success(toView(knowledgeBases.create(user, request.name(), request.description())));
    }

    @GetMapping
    TransDTO<List<KnowledgeBaseView>> list(@AuthenticationPrincipal CurrentUser user) {
        return TransDTO.success(knowledgeBases.listAccessible(user).stream().map(this::toView).toList());
    }

    @GetMapping("/{kbId}")
    TransDTO<KnowledgeBaseView> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId) {
        return TransDTO.success(toView(knowledgeBases.requireAccessible(user, kbId)));
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
                .map(member -> new MemberView(member.getUserId(), member.getRole()))
                .toList());
    }

    @PutMapping("/{kbId}/members")
    TransDTO<MemberView> upsertMember(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable long kbId,
                                      @Valid @RequestBody MemberUpsertRequest request) {
        KnowledgeBaseMember saved = knowledgeBases.upsertMember(
                user, kbId, request.userId(), request.role());
        return TransDTO.success(new MemberView(saved.getUserId(), saved.getRole()));
    }

    @DeleteMapping("/{kbId}/members/{userId}")
    TransDTO<Void> removeMember(@AuthenticationPrincipal CurrentUser user,
                                @PathVariable long kbId,
                                @PathVariable long userId) {
        knowledgeBases.removeMember(user, kbId, userId);
        return TransDTO.success();
    }

    private KnowledgeBaseView toView(KnowledgeBase kb) {
        return new KnowledgeBaseView(kb.getId(), kb.getUuid(), kb.getName(),
                kb.getDescription(), kb.getStatus());
    }
}
