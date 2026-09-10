package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.Attachment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/attachments")
@PreAuthorize("isAuthenticated()")
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    public record AttachmentView(String uuid, String fileName, String contentType,
                                 long byteSize, String status) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TransDTO<AttachmentView> upload(@AuthenticationPrincipal CurrentUser user,
                                    @PathVariable long kbId,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        Attachment stored = attachments.upload(user, kbId, file.getOriginalFilename(),
                file.getContentType(), file.getSize(), file.getInputStream());
        return TransDTO.success(toView(stored));
    }

    @GetMapping("/{attachmentUuid}/download-url")
    TransDTO<Map<String, String>> downloadUrl(@AuthenticationPrincipal CurrentUser user,
                                              @PathVariable long kbId,
                                              @PathVariable String attachmentUuid) {
        return TransDTO.success(Map.of("url", attachments.downloadUrl(user, kbId, attachmentUuid)));
    }

    /** 已授权的内联媒体预览：校验页面引用与生命周期。 */
    @GetMapping("/{attachmentUuid}/media-preview-url")
    TransDTO<Map<String, String>> mediaPreviewUrl(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable long kbId,
                                                  @PathVariable String attachmentUuid) {
        return TransDTO.success(Map.of(
                "url", attachments.mediaPreviewUrl(user, kbId, attachmentUuid)));
    }

    /** 稳定的内联字节 —— 写入导出文件的持久链接目标。 */
    @GetMapping("/{attachmentUuid}/content")
    ResponseEntity<byte[]> content(@AuthenticationPrincipal CurrentUser user,
                                   @PathVariable long kbId,
                                   @PathVariable String attachmentUuid) {
        AttachmentService.AttachmentContent content =
                attachments.readContent(user, kbId, attachmentUuid);
        String name = content.fileName() == null || content.fileName().isBlank()
                ? "attachment" : content.fileName();
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String type = content.contentType() == null || content.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : content.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(type))
                .body(content.bytes());
    }

    private AttachmentView toView(Attachment attachment) {
        return new AttachmentView(attachment.getUuid(), attachment.getFileName(),
                attachment.getContentType(), attachment.getByteSize(), attachment.getStatus());
    }
}
