package com.kwiki.indexing.multimodal;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多模态单元测试共享的内存版仓储：Mockito 代理 + Map 后备，
 * 覆盖 ImageResourceService 实际调用的少数方法。实体 id 通过
 * 反射注入（生产环境由 JPA 生成）。真实并发收敛行为由
 * DerivedImageRepositoryContractTest（外部 MySQL IT）覆盖。
 */
public final class TestMultimodalRepos {

    public static final class AssetBacking {
        public final Map<String, DerivedImageAsset> byKey = new ConcurrentHashMap<>();
        public final Map<Long, DerivedImageAsset> byId = new ConcurrentHashMap<>();
        public final AtomicLong ids = new AtomicLong(100);
    }

    public static final class SummaryBacking {
        public final Map<String, DerivedImageSummary> byKey = new ConcurrentHashMap<>();
        public final Map<Long, DerivedImageSummary> byId = new ConcurrentHashMap<>();
        public final AtomicLong ids = new AtomicLong(100);
    }

    public static DerivedImageAssetRepository assets(AssetBacking backing) {
        DerivedImageAssetRepository repo = mock(DerivedImageAssetRepository.class);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            DerivedImageAsset asset = inv.getArgument(0);
            if (asset.getId() == null) {
                assignId(asset, backing.ids.incrementAndGet());
            }
            backing.byId.put(asset.getId(), asset);
            backing.byKey.put(assetKey(asset), asset);
            return asset;
        });
        when(repo.save(any())).thenAnswer(inv -> {
            DerivedImageAsset asset = inv.getArgument(0);
            backing.byId.put(asset.getId(), asset);
            backing.byKey.put(assetKey(asset), asset);
            return asset;
        });
        when(repo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            DerivedImageAsset asset = backing.byId.get(id);
            // 模拟乐观读：每次返回行内的最新状态（同一对象引用）
            return Optional.ofNullable(asset);
        });
        when(repo.findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                any(), any(), any(), any())).thenAnswer(inv -> Optional.ofNullable(
                backing.byKey.get(assetKey(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)))));
        return repo;
    }

    public static DerivedImageSummaryRepository summaries(SummaryBacking backing) {
        DerivedImageSummaryRepository repo = mock(DerivedImageSummaryRepository.class);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            DerivedImageSummary summary = inv.getArgument(0);
            if (summary.getId() == null) {
                assignId(summary, backing.ids.incrementAndGet());
            }
            backing.byId.put(summary.getId(), summary);
            backing.byKey.put(summaryKey(summary), summary);
            return summary;
        });
        when(repo.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(backing.byId.get(inv.getArgument(0))));
        when(repo.findByAssetIdAndModelAndPromptVersion(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(backing.byKey.get(summaryKey(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))));
        return repo;
    }

    static String assetKey(DerivedImageAsset asset) {
        return assetKey(asset.getSourceKind(), asset.getSourceRef(),
                asset.getParserVersion(), asset.getImageSha256());
    }

    static String assetKey(String kind, String ref, String parser, String hash) {
        return kind + "|" + ref + "|" + parser + "|" + hash;
    }

    static String summaryKey(DerivedImageSummary summary) {
        return summaryKey(summary.getAssetId(), summary.getModel(), summary.getPromptVersion());
    }

    static String summaryKey(Long assetId, String model, String promptVersion) {
        return assetId + "|" + model + "|" + promptVersion;
    }

    private static void assignId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TestMultimodalRepos() {
    }
}
