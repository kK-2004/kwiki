package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}/audience")
@PreAuthorize("isAuthenticated()")
public class ResourceAudienceController {
    private final ResourceAudienceService audience;
    public ResourceAudienceController(ResourceAudienceService audience) { this.audience = audience; }
    public record UpdateRequest(@NotBlank String mode, List<ResourceAudienceService.Member> members) {}

    @GetMapping
    TransDTO<ResourceAudienceService.AudienceView> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long pageId) { return TransDTO.success(audience.get(user, pageId)); }

    @PutMapping
    TransDTO<ResourceAudienceService.AudienceView> update(@AuthenticationPrincipal CurrentUser user, @PathVariable long pageId, @Valid @RequestBody UpdateRequest request) { return TransDTO.success(audience.update(user, pageId, request.mode(), request.members())); }

    @GetMapping("/candidates")
    TransDTO<List<ResourceAudienceService.User>> candidates(@AuthenticationPrincipal CurrentUser user, @PathVariable long pageId, @RequestParam long sourceKbId, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "20") int limit) { return TransDTO.success(audience.candidates(user, pageId, sourceKbId, q, limit)); }

}
