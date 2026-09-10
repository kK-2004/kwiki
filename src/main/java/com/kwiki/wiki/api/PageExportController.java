package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 导出端点：POST 精确快照（编辑器草稿，可能尚未保存）或
 * 修订版本号（阅读器视图）。始终以单个 Markdown/HTML
 * 文件作答；attachment:// 引用指向持久的内容端点。
 * 这里不会发布或持久化任何内容。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}/export")
@PreAuthorize("isAuthenticated()")
public class PageExportController {

    private final PageExportService exports;
    private final String publicBaseUrl;

    public PageExportController(PageExportService exports,
                                @Value("${kwiki.export.public-base-url:}") String publicBaseUrl) {
        this.exports = exports;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record ExportRequest(String format, String markdown, Integer revisionNo) {}

    @PostMapping
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser user,
                                         @PathVariable long kbId,
                                         @PathVariable long pageId,
                                         @RequestBody ExportRequest request) {
        String baseUrl = StringUtils.hasText(publicBaseUrl)
                ? publicBaseUrl
                : ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        var payload = exports.export(user, kbId, pageId,
                request.format() == null ? "md" : request.format(),
                request.markdown(),
                request.revisionNo(),
                baseUrl);
        String encodedName = URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.content());
    }
}
