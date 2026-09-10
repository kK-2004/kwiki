package com.kwiki.wiki.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容标识的生命周期规则：PENDING 附件可以没有内容中心
 * 的 file id，而只有经过校验的正数 file id 才能完成 STORED 转换。
 */
class AttachmentTest {

    private static Attachment pending() {
        return new Attachment("att-uuid", 1L, 2L, "spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100);
    }

    @Test
    void newAttachmentIsPendingWithoutFileId() {
        Attachment attachment = pending();

        assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_PENDING);
        assertThat(attachment.getContentCenterFileId()).isNull();
    }

    @Test
    void markStoredRequiresPositiveContentCenterFileId() {
        Attachment attachment = pending();

        assertThatThrownBy(() -> attachment.markStored(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attachment.markStored(-7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_PENDING);
        assertThat(attachment.getContentCenterFileId()).isNull();
    }

    @Test
    void markStoresSetsFileIdAndStoredStatus() {
        Attachment attachment = pending();

        attachment.markStored(42L);

        assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_STORED);
        assertThat(attachment.getContentCenterFileId()).isEqualTo(42L);
    }

    @Test
    void archiveKeepsPersistedFileIdForAudit() {
        Attachment attachment = pending();
        attachment.markStored(42L);

        attachment.archive();

        assertThat(attachment.getStatus()).isEqualTo(Attachment.STATUS_ARCHIVED);
        assertThat(attachment.getContentCenterFileId()).isEqualTo(42L);
    }
}
