package com.kwiki.indexing.multimodal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派生图片持久层的并发契约（通过 KWIKI_IT_MYSQL_* 使用运维方提供的
 * MySQL）：唯一约束保证并发 worker 对同一（源, 解析器版本, 图片哈希）
 * 身份最终收敛到一行权威记录；摘要同理按（asset, model, prompt-version）
 * 收敛。已完成的中间状态（contentId / READY 摘要）可被后续 worker
 * 读取复用，绝不重复上传或重复摘要。
 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DerivedImageRepositoryContractTest {

    @DynamicPropertySource
    static void externalDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KWIKI_IT_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("KWIKI_IT_MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("KWIKI_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    DerivedImageAssetRepository assets;

    @Autowired
    DerivedImageSummaryRepository summaries;

    private static String sha(String seed) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentWorkersConvergeOnOneAuthoritativeAsset() throws Exception {
        String hash = sha("asset-converge");
        AtomicInteger inserts = new AtomicInteger();
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<DerivedImageAsset>> results = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        DerivedImageAsset asset = new DerivedImageAsset(
                                DerivedImageAsset.KIND_ATTACHMENT_PDF, "attachment:9001", 7L,
                                "kwiki-parse-2", hash);
                        inserts.incrementAndGet();
                        return assets.saveAndFlush(asset);
                    } catch (DataIntegrityViolationException duplicate) {
                        // 并发竞争落败者：唯一约束把执行者收敛到已存在行
                        return assets.findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                                DerivedImageAsset.KIND_ATTACHMENT_PDF, "attachment:9001",
                                "kwiki-parse-2", hash).orElseThrow();
                    }
                }));
            }
            start.countDown();
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (Future<DerivedImageAsset> future : results) {
                ids.add(future.get(30, TimeUnit.SECONDS).getId());
            }
            assertThat(ids).doesNotContainNull();
            // 全部 worker 最终读到同一行权威身份
            assertThat(ids.stream().distinct()).hasSize(1);
            assertThat(assets.findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                            DerivedImageAsset.KIND_ATTACHMENT_PDF, "attachment:9001",
                            "kwiki-parse-2", hash))
                    .isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completedUploadIsReusedAndCannotBeOverwrittenWithADifferentId() {
        String hash = sha("asset-reuse");
        DerivedImageAsset asset = assets.saveAndFlush(new DerivedImageAsset(
                DerivedImageAsset.KIND_EXTERNAL_URL, "url:abc", 3L, "kwiki-parse-2", hash));
        asset.markUploaded(4242L, "image/png", 1024L);
        assets.saveAndFlush(asset);

        DerivedImageAsset retried = assets
                .findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                        DerivedImageAsset.KIND_EXTERNAL_URL, "url:abc", "kwiki-parse-2", hash)
                .orElseThrow();
        assertThat(retried.getContentId()).isEqualTo(4242L);
        assertThat(retried.hasAuthoritativeContent()).isTrue();

        // 重试 worker 不得覆盖已有权威 contentId
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> retried.markUploaded(9999L, "image/png", 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promptVersionBumpCreatesIndependentSummaryIdentityForSameAsset() {
        String hash = sha("summary-identity");
        DerivedImageAsset asset = assets.saveAndFlush(new DerivedImageAsset(
                DerivedImageAsset.KIND_ATTACHMENT_IMAGE, "attachment:77", 5L,
                "kwiki-parse-2", hash));
        asset.markUploaded(11L, "image/jpeg", 2048L);
        assets.saveAndFlush(asset);

        DerivedImageSummary v1 = summaries.saveAndFlush(
                new DerivedImageSummary(asset.getId(), "qwen3.7-flash", "v1"));
        v1.markReady("v1 摘要");
        summaries.saveAndFlush(v1);

        // 重试复用 READY 摘要；新提示词版本得到新的独立身份
        assertThat(summaries.findByAssetIdAndModelAndPromptVersion(
                asset.getId(), "qwen3.7-flash", "v1")).hasValueSatisfying(
                summary -> assertThat(summary.isUsable()).isTrue());
        DerivedImageSummary v2 = summaries.saveAndFlush(
                new DerivedImageSummary(asset.getId(), "qwen3.7-flash", "v2"));
        v2.markReady("v2 摘要");
        summaries.saveAndFlush(v2);
        assertThat(summaries.findByAssetIdAndModelAndPromptVersion(
                asset.getId(), "qwen3.7-flash", "v2")).hasValueSatisfying(
                summary -> assertThat(summary.getSummary()).isEqualTo("v2 摘要"));
    }

    @Test
    void orphanCandidateMarkingIsIdempotentAndBounded() {
        String hash = sha("cleanup-mark");
        DerivedImageAsset asset = assets.saveAndFlush(new DerivedImageAsset(
                DerivedImageAsset.KIND_ATTACHMENT_PDF, "attachment:9002", 9L,
                "kwiki-parse-2", hash));
        asset.markUploaded(555L, "image/png", 10L);
        assets.saveAndFlush(asset);

        int first = assets.markOrphanCandidates(java.util.List.of(asset.getId()));
        int second = assets.markOrphanCandidates(java.util.List.of(asset.getId()));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(assets.findById(asset.getId()).orElseThrow().getCleanupState())
                .isEqualTo(DerivedImageAsset.CLEANUP_ORPHAN_CANDIDATE);
    }
}
