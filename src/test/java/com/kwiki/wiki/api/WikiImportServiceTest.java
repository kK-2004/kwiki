package com.kwiki.wiki.api;

import com.kwiki.indexing.parse.DocumentParseService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiImportServiceTest {

    private WikiImportService service() {
        return new WikiImportService(null, null, null, null, null);
    }

    @Test
    void acceptsPdfUploadWithPdfMimeType() {
        assertThatCode(() -> service().validateUpload(
                "员工手册.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPdfWithWrongMimeType() {
        assertThatThrownBy(() -> service().validateUpload(
                "员工手册.pdf", "application/octet-stream+pdf", "%PDF-1.7".getBytes()))
                .isInstanceOf(WikiImportValidationException.class)
                .hasMessageContaining("类型与扩展名不一致");
    }

    @Test
    void rejectsPdfWithoutPdfSignature() {
        assertThatThrownBy(() -> service().validateUpload(
                "员工手册.pdf", "application/pdf", "not a pdf".getBytes()))
                .isInstanceOf(WikiImportValidationException.class)
                .hasMessageContaining("PDF 文件结构无效");
    }
}
