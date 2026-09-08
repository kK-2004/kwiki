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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/imports")
@PreAuthorize("isAuthenticated()")
public class WikiImportController {
    private final WikiImportJobService imports;
    public WikiImportController(WikiImportJobService imports) { this.imports = imports; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TransDTO<WikiImportJobService.ImportJobView> upload(@AuthenticationPrincipal CurrentUser user,
                                                    @PathVariable long kbId,
                                                    @RequestParam(required = false) Long parentId,
                                                    @RequestParam(defaultValue = "KB_MEMBERS") String audienceMode,
                                                    @RequestParam(defaultValue = "") String audienceMembers,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return TransDTO.success(imports.submit(user, kbId, parentId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), audienceMode, parseAudienceMembers(audienceMembers), idempotencyKey));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{jobId}")
    TransDTO<WikiImportJobService.ImportJobView> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable long jobId) {
        return TransDTO.success(imports.detail(user, jobId));
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
