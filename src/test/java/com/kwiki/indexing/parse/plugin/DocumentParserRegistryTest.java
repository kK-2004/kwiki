package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserRegistryTest {
    @Test
    void 收集两个解析器并按id查询与排序() {
        var second = new FakeParser("fake-b", "解析器 B");
        var first = new FakeParser("fake-a", "解析器 A");
        var registry = new DocumentParserRegistry(List.of(second, first));

        assertThat(registry.kind()).isEqualTo("解析器");
        assertThat(registry.all()).containsExactly(first, second);
        assertThat(registry.find("fake-a")).containsSame(first);
        assertThat(registry.require("fake-b")).isSameAs(second);
        assertThat(registry.find("missing-parser")).isEmpty();
    }

    @Test
    void 未注册id抛出包含解析器类别与id的异常() {
        var registry = new DocumentParserRegistry(List.of(new FakeParser("fake-a", "解析器 A")));

        assertThatThrownBy(() -> registry.require("missing-parser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解析器")
                .hasMessageContaining("missing-parser");
    }

    private record FakeParser(String id, String label) implements DocumentParserPlugin {
        @Override
        public Set<IndexCapability> requiredCapabilities() {
            return Set.of(IndexCapability.RESOURCE_IDS);
        }

        @Override
        public Set<String> indexableAttachmentTypes() {
            return Set.of("application/pdf");
        }

        @Override
        public StructuredDocument parsePage(PageParseRequest request) {
            throw new UnsupportedOperationException("注册表测试不执行解析");
        }

        @Override
        public StructuredDocument parseAttachment(AttachmentParseRequest request) {
            throw new UnsupportedOperationException("注册表测试不执行解析");
        }

        @Override
        public StructuredDocument parseImport(ImportParseRequest request) {
            throw new UnsupportedOperationException("注册表测试不执行解析");
        }
    }
}
