package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}/anchors")
@PreAuthorize("isAuthenticated()")
public class SelectionAnchorController {
    private final SelectionAnchorService anchors;
    public SelectionAnchorController(SelectionAnchorService anchors) { this.anchors = anchors; }
    public record Request(@NotBlank String selectedText, Integer startOffset, Integer endOffset) {}
    @PostMapping
    TransDTO<SelectionAnchorService.AnchorView> create(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId, @PathVariable long pageId, @Valid @RequestBody Request request) { return TransDTO.success(anchors.create(user, kbId, pageId, request.selectedText(), request.startOffset(), request.endOffset())); }

    @GetMapping("/{anchorId}")
    TransDTO<SelectionAnchorService.AnchorResolution> resolve(@AuthenticationPrincipal CurrentUser user, @PathVariable long kbId,
                                                               @PathVariable long pageId, @PathVariable long anchorId) {
        return TransDTO.success(anchors.resolve(user, kbId, pageId, anchorId));
    }
}
