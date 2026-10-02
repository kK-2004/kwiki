package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 只计算人工清理建议；不会创建定时任务，也不会删除数据库或 ES 数据。 */
@Service
public class IndexVersionRetentionAdvisor {

    private static final int DEFAULT_RETAINED_VERSIONS = 2;
    private final SearchIndexVersionRepository versions;

    public IndexVersionRetentionAdvisor(SearchIndexVersionRepository versions) {
        this.versions = versions;
    }

    /** 灰度版本的知识库范围；未注入（离线测试）时视为全部为全局版本。 */
    private com.kwiki.indexing.gray.IndexVersionKbScope kbScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbScope(com.kwiki.indexing.gray.IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }

    private boolean scoped(SearchIndexVersion version) {
        return kbScope != null && kbScope.isScoped(version.getVersionNumber());
    }

    @Transactional(readOnly = true)
    public Recommendation recommend() {
        List<SearchIndexVersion> active = versions
                .findByDeletedAtIsNullOrderByVersionNumberAsc();
        Set<Integer> retained = new LinkedHashSet<>();
        active.stream().filter(SearchIndexVersion::isSelected)
                .forEach(version -> retained.add(version.getVersionNumber()));
        // 灰度版本不占保留名额：结束灰度关闭写入后即可成为清理候选
        active.stream()
                .filter(version -> !scoped(version))
                .sorted(Comparator.comparingInt(SearchIndexVersion::getVersionNumber).reversed())
                .map(SearchIndexVersion::getVersionNumber)
                .filter(version -> !retained.contains(version))
                .limit(Math.max(0, DEFAULT_RETAINED_VERSIONS - retained.size()))
                .forEach(retained::add);

        List<VersionRecommendation> items = new ArrayList<>();
        for (SearchIndexVersion version : active) {
            boolean keep = retained.contains(version.getVersionNumber());
            // 写入关闭即可清理：从未开启过写入的版本没有 adminDisabled 标记，也应能被清理
            boolean cleanupCandidate = !keep
                    && !version.isSelected() && !version.isWriteEnabled();
            items.add(new VersionRecommendation(version.getVersionNumber(),
                    version.getPhysicalName(), keep, cleanupCandidate));
        }
        return new Recommendation(DEFAULT_RETAINED_VERSIONS, List.copyOf(items));
    }

    public record Recommendation(int defaultRetainedCount,
                                 List<VersionRecommendation> versions) { }

    public record VersionRecommendation(int versionNumber, String physicalName,
                                        boolean retained,
                                        boolean cleanupCandidate) { }
}
