package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/invitations")
@PreAuthorize("isAuthenticated()")
public class InvitationController {
    private final InvitationService invitations;
    public InvitationController(InvitationService invitations) { this.invitations = invitations; }

    public record CreateRequest(@NotBlank String resourceType, long resourceId, @NotBlank String role, Long ttlSeconds) {}

    @PostMapping
    TransDTO<InvitationService.InvitationCreated> create(@AuthenticationPrincipal CurrentUser user,
                                                         @Valid @RequestBody CreateRequest request) {
        return TransDTO.success(invitations.create(user, request.resourceType(), request.resourceId(), request.role(), request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds())));
    }

    @GetMapping("/{token}")
    TransDTO<InvitationService.InvitationView> preview(@AuthenticationPrincipal CurrentUser user, @PathVariable String token) {
        return TransDTO.success(invitations.preview(user, token));
    }

    @GetMapping
    TransDTO<java.util.List<InvitationService.InvitationListView>> list(@AuthenticationPrincipal CurrentUser user,
                                                                         @RequestParam String resourceType,
                                                                         @RequestParam long resourceId) {
        return TransDTO.success(invitations.list(user, resourceType, resourceId));
    }

    @PostMapping("/{token}/accept")
    TransDTO<InvitationService.Acceptance> accept(@AuthenticationPrincipal CurrentUser user, @PathVariable String token) {
        return TransDTO.success(invitations.accept(user, token));
    }

    @DeleteMapping("/{invitationId}")
    TransDTO<Void> revoke(@AuthenticationPrincipal CurrentUser user, @PathVariable long invitationId) {
        invitations.revoke(user, invitationId); return TransDTO.success();
    }

    @GetMapping("/requests")
    TransDTO<java.util.List<InvitationService.JoinRequestView>> requests(@AuthenticationPrincipal CurrentUser user, @RequestParam String resourceType, @RequestParam long resourceId) {
        return TransDTO.success(invitations.requests(user, resourceType, resourceId));
    }

    public record ReviewRequest(boolean approve) {}
    @PutMapping("/requests/{requestId}")
    TransDTO<Void> review(@AuthenticationPrincipal CurrentUser user, @PathVariable long requestId, @Valid @RequestBody ReviewRequest request) {
        invitations.review(user, requestId, request.approve()); return TransDTO.success();
    }
}
