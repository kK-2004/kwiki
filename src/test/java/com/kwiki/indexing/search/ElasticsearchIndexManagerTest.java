package com.kwiki.indexing.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 显式物理索引创建的幂等与冲突语义：同名已存在且 mapping 兼容 →
 * 接受并继续；同名但映射冲突（例如维度不同）→ 拒绝。
 */
class ElasticsearchIndexManagerTest {

    @SuppressWarnings("unchecked")
    private static <B, T> java.util.function.Function<B, co.elastic.clients.util.ObjectBuilder<T>> anyFn() {
        return any(java.util.function.Function.class);
    }

    private static co.elastic.clients.elasticsearch._types.ElasticsearchException alreadyExists() {
        co.elastic.clients.elasticsearch._types.ElasticsearchException exception =
                mock(co.elastic.clients.elasticsearch._types.ElasticsearchException.class);
        co.elastic.clients.elasticsearch._types.ErrorCause error =
                mock(co.elastic.clients.elasticsearch._types.ErrorCause.class);
        when(exception.error()).thenReturn(error);
        when(error.type()).thenReturn("resource_already_exists_exception");
        return exception;
    }

    /** 可注入"已存在冲突"与"复核结果"的测试替身。 */
    private static final class TestingManager extends ElasticsearchIndexManager {
        boolean indexAlreadyExists;
        String revalidationResult;

        TestingManager() {
            super(providerOf(mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class)),
                    new ChunkMappingBuilder());
        }

        private static <T> ObjectProvider<T> providerOf(T value) {
            return new ObjectProvider<>() {
                @Override
                public T getIfAvailable() {
                    return value;
                }
            };
        }

        @Override
        void doCreateIndex(String indexName, String mappingJson) {
            if (indexAlreadyExists) {
                throw alreadyExists();
            }
        }

        @Override
        public String validateIndex(String indexName, int expectedDimensions) {
            return revalidationResult;
        }
    }

    @Test
    void sameNameIndexWithCompatibleMappingIsAcceptedIdempotently() throws Exception {
        TestingManager manager = new TestingManager();
        manager.indexAlreadyExists = true;
        manager.revalidationResult = null; // 兼容

        String name = manager.createVersionedIndex("kwiki-chunks-v2", 1024);

        assertThat(name).isEqualTo("kwiki-chunks-v2");
    }

    @Test
    void sameNameIndexWithConflictingMappingIsRejected() {
        TestingManager manager = new TestingManager();
        manager.indexAlreadyExists = true;
        manager.revalidationResult = "vector dimension mismatch: expected 1024";

        assertThatThrownBy(() -> manager.createVersionedIndex("kwiki-chunks-v2", 2048))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kwiki-chunks-v2 conflicts")
                .hasMessageContaining("vector dimension mismatch");
    }

    @Test
    void freshCreateIsAccepted() throws Exception {
        TestingManager manager = new TestingManager();
        manager.indexAlreadyExists = false;

        assertThat(manager.createVersionedIndex("kwiki-chunks-v3", 1024))
                .isEqualTo("kwiki-chunks-v3");
    }
}
