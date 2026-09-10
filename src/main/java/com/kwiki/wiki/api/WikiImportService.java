package com.kwiki.wiki.api;

import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 解析文本文档，并以事务方式创建真实的 Wiki 页面/修订版本。 */
@Service
public class WikiImportService {
    private static final long MAX_IMPORT_BYTES = 20 * 1024 * 1024;
    private final DocumentParseService parser;
    private final WikiTreeService tree;
    private final PageRevisionService revisions;
    private final ResourceAudienceService audience;
    private final SourceDocumentRepository sources;

    public WikiImportService(DocumentParseService parser, WikiTreeService tree, PageRevisionService revisions, ResourceAudienceService audience) {
        this(parser, tree, revisions, audience, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WikiImportService(DocumentParseService parser, WikiTreeService tree, PageRevisionService revisions,
                             ResourceAudienceService audience, SourceDocumentRepository sources) {
        this.parser = parser; this.tree = tree; this.revisions = revisions; this.audience = audience; this.sources = sources;
    }

    @Transactional
    public ImportedPage importDocument(CurrentUser user, long kbId, Long parentId, String fileName,
                                      String contentType, byte[] content) {
        return importDocument(user, kbId, parentId, fileName, contentType, content, "KB_MEMBERS");
    }

    @Transactional
    public ImportedPage importDocument(CurrentUser user, long kbId, Long parentId, String fileName,
                                      String contentType, byte[] content, String audienceMode) {
        return importDocument(user, kbId, parentId, fileName, contentType, content, audienceMode, List.of());
    }

    @Transactional
    public ImportedPage importDocument(CurrentUser user, long kbId, Long parentId, String fileName,
                                      String contentType, byte[] content, String audienceMode,
                                      List<ResourceAudienceService.Member> audienceMembers) {
        return importDocument(user, kbId, parentId, fileName, contentType, content, audienceMode, audienceMembers, null);
    }

    @Transactional
    public ImportedPage importDocument(CurrentUser user, long kbId, Long parentId, String fileName,
                                      String contentType, byte[] content, String audienceMode,
                                      List<ResourceAudienceService.Member> audienceMembers, Long sourceAttachmentId) {
        validateUpload(fileName, contentType, content);
        String safeName = fileName == null || fileName.isBlank() ? "导入文档" : fileName;
        List<String> warnings = validateImportShape(safeName, content);
        StructuredDocument document = parser.parse(safeName, contentType, new ByteArrayInputStream(content));
        String markdown = markdown(safeName, content, document);
        String title = title(safeName);
        WikiPage page = tree.createNode(user, kbId, parentId, title, WikiPage.TYPE_PAGE, null);
        revisions.saveDraft(user, kbId, page.getId(), markdown, "导入 " + safeName, null);
        revisions.publish(user, kbId, page.getId());
        String mode = audienceMode == null ? "KB_MEMBERS" : audienceMode.trim().toUpperCase(Locale.ROOT);
        if ("SELECTED_MEMBERS".equals(mode) && (audienceMembers == null || audienceMembers.isEmpty())) {
            throw new IllegalArgumentException("selected audience requires members");
        }
        if ("PRIVATE".equals(mode)) audience.update(user, page.getId(), mode, java.util.List.of());
        else if ("SELECTED_MEMBERS".equals(mode)) audience.update(user, page.getId(), mode, audienceMembers);
        if (sourceAttachmentId != null && sources != null) {
            sources.save(new SourceDocument(page.getId(), sourceAttachmentId, SourceDocument.REL_DERIVED_FROM));
        }
        return new ImportedPage(page.getId(), page.getUuid(), title, document.blocks().size(), warnings);
    }

    /** 在存储任何持久附件之前校验 multipart 边界。 */
    public void validateUpload(String fileName, String contentType, byte[] content) {
        try { validateUploadChecked(fileName, contentType, content); }
        catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("size")) throw new WikiImportValidationException("请选择非空文件，大小不超过 20 MB");
            if (message.contains("UTF-8")) throw new WikiImportValidationException("Markdown 文件需要使用 UTF-8 编码");
            if (message.contains("only Markdown")) throw new WikiImportValidationException("仅支持 Markdown 和 DOCX 文件");
            if (message.contains("content type")) throw new WikiImportValidationException("文件类型与扩展名不一致，请重新另存为 Markdown 或 DOCX");
            throw new WikiImportValidationException("DOCX 文件结构无效，或包含不支持的宏、嵌入文件，请重新另存后上传");
        }
    }

    private void validateUploadChecked(String fileName, String contentType, byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("file size is out of range");
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean markdown = lower.endsWith(".md") || lower.endsWith(".markdown");
        boolean docx = lower.endsWith(".docx");
        if (!markdown && !docx) throw new IllegalArgumentException("only Markdown and DOCX imports are supported");
        if (docx && !mime.isBlank() && !"application/octet-stream".equals(mime)
                && !"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            throw new IllegalArgumentException("DOCX content type is not allowed");
        }
        if (markdown && !mime.isBlank() && !"application/octet-stream".equals(mime)
                && !"text/markdown".equals(mime) && !"text/plain".equals(mime)) {
            throw new IllegalArgumentException("Markdown content type is not allowed");
        }
        validateImportShape(lower, content);
    }

    private String markdown(String fileName, byte[] content, StructuredDocument document) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return new String(content, StandardCharsets.UTF_8);
        return document.blocks().stream().map(block -> (block.headingLevel() > 0 ? "#".repeat(block.headingLevel()) + " " : "") + block.text()).reduce((a, b) -> a + "\n\n" + b).orElse(document.plainText());
    }

    private String title(String fileName) { String name = fileName.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1); int dot = name.lastIndexOf('.'); return (dot > 0 ? name.substring(0, dot) : name).trim(); }
    private List<String> validateImportShape(String fileName, byte[] content) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".docx"))) {
            throw new IllegalArgumentException("only Markdown and DOCX imports are supported");
        }
        if (lower.endsWith(".docx")) {
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
                throw new IllegalArgumentException("DOCX signature is invalid");
            }
            return validateDocxArchive(content);
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Markdown must be valid UTF-8");
        }
        return List.of();
    }

    private List<String> validateDocxArchive(byte[] content) {
        List<String> warnings = new ArrayList<>();
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 2000) throw new IllegalArgumentException("DOCX contains too many archive entries");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IllegalArgumentException("DOCX contains an unsafe archive path");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith("vbaproject.bin") || lower.contains("/embeddings/")) {
                    throw new IllegalArgumentException("DOCX macros and embedded files are not supported");
                }
                if (lower.startsWith("word/media/")) warnings.add("文档包含嵌入图片，图片内容未导入");
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_IMPORT_BYTES * 4) throw new IllegalArgumentException("DOCX expands beyond the safe extraction limit");
                }
            }
            if (entries == 0) throw new IllegalArgumentException("DOCX archive is empty");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("DOCX archive is invalid");
        }
        return warnings.stream().distinct().toList();
    }
    public record ImportedPage(long id, String uuid, String title, int blockCount, List<String> warnings) {}
}
