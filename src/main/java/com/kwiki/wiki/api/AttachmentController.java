package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.Attachment;
import org.springframework.http.MediaType;
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

    private AttachmentView toView(Attachment attachment) {
        return new AttachmentView(attachment.getUuid(), attachment.getFileName(),
                attachment.getContentType(), attachment.getByteSize(), attachment.getStatus());
    }
}
