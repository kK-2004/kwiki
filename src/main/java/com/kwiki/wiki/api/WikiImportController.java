package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/imports")
@PreAuthorize("isAuthenticated()")
public class WikiImportController {
    private final WikiImportJobService imports;
    public WikiImportController(WikiImportJobService imports) { this.imports = imports; }

    public record ImportRequest(String attachmentUuid, Long parentId, String audienceMode, String audienceMembers) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    TransDTO<WikiImportJobService.ImportJobView> upload(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable long kbId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                      @RequestBody ImportRequest request) {
        return TransDTO.success(imports.submitStored(user, kbId, request.parentId(), request.attachmentUuid(),
                request.audienceMode(), parseAudienceMembers(request.audienceMembers()), idempotencyKey));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{jobId}")
    TransDTO<WikiImportJobService.ImportJobView> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable long jobId) {
        return TransDTO.success(imports.detail(user, jobId));
    }

    @org.springframework.web.bind.annotation.GetMapping
    TransDTO<List<WikiImportJobService.ImportJobView>> list(@AuthenticationPrincipal CurrentUser user,
                                                            @PathVariable long kbId,
                                                            @RequestParam(defaultValue = "100") int limit) {
        return TransDTO.success(imports.list(user, kbId, limit));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{jobId}/retry")
    TransDTO<WikiImportJobService.ImportJobView> retry(@AuthenticationPrincipal CurrentUser user, @PathVariable long jobId) {
        return TransDTO.success(imports.retry(user, jobId));
    }

    private List<ResourceAudienceService.Member> parseAudienceMembers(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<ResourceAudienceService.Member> members = new ArrayList<>();
        for (String token : value.split(",")) {
            String[] pair = token.trim().split(":", -1);
            if (pair.length != 2) throw new IllegalArgumentException("invalid audience member");
            try { members.add(new ResourceAudienceService.Member(Long.parseLong(pair[0]), Long.parseLong(pair[1]))); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid audience member"); }
        }
        return members;
    }
}
