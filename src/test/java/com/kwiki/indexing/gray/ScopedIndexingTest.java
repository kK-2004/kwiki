package com.kwiki.indexing.gray;

import com.kwiki.indexing.version.FixedRangeRebuildScanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源扫描 SQL 必须带版本知识库范围过滤：以源码契约方式断言，
 * 防止后续修改扫描语句时遗漏范围（行为由 IndexVersionKbScopeTest 与集成验证覆盖）。
 */
class ScopedIndexingTest {

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/kwiki/" + relative));
    }

    @Test
    void 重建扫描的页面与附件查询都带范围过滤() throws Exception {
        String scanner = source("indexing/version/FixedRangeRebuildScanner.java");
        assertThat(scanner).contains("IndexVersionKbScope.sqlFilter(\"p.kb_id\")")
                .contains("IndexVersionKbScope.sqlFilter(\"a.kb_id\")");
    }

    @Test
    void 校验资源清单带范围过滤() throws Exception {
        String validation = source("indexing/version/SearchIndexValidationService.java");
        assertThat(validation).contains("IndexVersionKbScope.sqlFilter(\"p.kb_id\")")
                .contains("IndexVersionKbScope.sqlFilter(\"a.kb_id\")");
    }

    @Test
    void 写入前判断目标版本是否接受该知识库() throws Exception {
        String worker = source("indexing/job/IndexingWorker.java");
        assertThat(worker).contains("acceptsKnowledgeBase(indexVersion, page.getKbId())")
                .contains("acceptsKnowledgeBase(indexVersion, attachment.getKbId())");
    }

    @Test
    void 扫描器类仍存在() {
        assertThat(FixedRangeRebuildScanner.class).isNotNull();
    }
}
