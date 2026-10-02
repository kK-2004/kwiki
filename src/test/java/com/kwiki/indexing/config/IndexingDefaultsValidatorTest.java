package com.kwiki.indexing.config;
import com.kwiki.indexing.parse.plugin.*;
import com.kwiki.indexing.schema.SchemaTestFixtures;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
class IndexingDefaultsValidatorTest {
    @Test void 默认绑定保留解析器偏好() {
        assertThat(new IndexingDefaultsProperties("kwiki-schema-1").parsers()).containsExactly("kwiki-parse-2", "kwiki-parse-1");
    }
    @Test void 空列表和未知解析器使启动失败() {
        for (List<String> ids : List.of(List.<String>of(), List.of("unknown-parser", "kwiki-parse-1"))) {
            assertThatThrownBy(() -> new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1", ids),
                    SchemaTestFixtures.schemas(), ParserTestFixtures.availableBoth())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void 最后解析器必须可用而前项允许不可用() {
        var registry = ParserTestFixtures.unavailableTwo();
        new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1"), SchemaTestFixtures.schemas(), registry);
        assertThatThrownBy(() -> new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1",
                List.of("kwiki-parse-1", "kwiki-parse-2")), SchemaTestFixtures.schemas(), registry))
                .hasMessageContaining("基线解析器不可用");
    }
}
