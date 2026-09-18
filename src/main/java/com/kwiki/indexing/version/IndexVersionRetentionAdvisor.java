package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 只计算人工清理建议；不会创建定时任务，也不会删除数据库或 ES 数据。 */
@Service
@ConditionalOnBean(SearchIndexVersionRepository.class)
public class IndexVersionRetentionAdvisor {

    private static final int DEFAULT_RETAINED_VERSIONS = 2;
    private final SearchIndexVersionRepository versions;

    public IndexVersionRetentionAdvisor(SearchIndexVersionRepository versions) {
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public Recommendation recommend() {
        List<SearchIndexVersion> active = versions
                .findByDeletedAtIsNullOrderByVersionNumberAsc();
        Set<Integer> retained = new LinkedHashSet<>();
        active.stream().filter(SearchIndexVersion::isSelected)
                .forEach(version -> retained.add(version.getVersionNumber()));
        active.stream()
                .sorted(Comparator.comparingInt(SearchIndexVersion::getVersionNumber).reversed())
                .map(SearchIndexVersion::getVersionNumber)
                .filter(version -> !retained.contains(version))
                .limit(Math.max(0, DEFAULT_RETAINED_VERSIONS - retained.size()))
                .forEach(retained::add);

        List<VersionRecommendation> items = new ArrayList<>();
        for (SearchIndexVersion version : active) {
            boolean keep = retained.contains(version.getVersionNumber());
            boolean cleanupCandidate = !keep && version.isAdminDisabled()
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
