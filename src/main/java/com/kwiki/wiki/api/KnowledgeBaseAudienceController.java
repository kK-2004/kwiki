package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 供创建前受众选择器使用的候选查询。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/audience-candidates")
@PreAuthorize("isAuthenticated()")
public class KnowledgeBaseAudienceController {
    private final ResourceAudienceService audience;

    public KnowledgeBaseAudienceController(ResourceAudienceService audience) { this.audience = audience; }

    @GetMapping
    TransDTO<List<ResourceAudienceService.User>> candidates(@AuthenticationPrincipal CurrentUser user,
                                                             @PathVariable long kbId,
                                                             @RequestParam long sourceKbId,
                                                             @RequestParam(defaultValue = "") String q,
                                                             @RequestParam(defaultValue = "20") int limit) {
        return TransDTO.success(audience.candidatesInKnowledgeBase(user, kbId, sourceKbId, q, limit));
    }
}
