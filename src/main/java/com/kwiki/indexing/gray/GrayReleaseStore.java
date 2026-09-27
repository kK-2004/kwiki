package com.kwiki.indexing.gray;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 灰度发布存储。实现须保证 activeReleaseByKb 与唯一约束一致。 */
public interface GrayReleaseStore {

    /** 插入 CREATED 状态的灰度，返回 id。 */
    long insert(String name, String parserVersion, int indexVersionNumber, String createdBy);

    /** 插入知识库并设置 active_kb_id；违反唯一约束时抛 DataIntegrityViolationException。 */
    void insertKbs(long releaseId, Collection<Long> kbIds);

    Optional<GrayRelease> find(long id);

    List<GrayRelease> findAll();

    /** 按灰度索引版本号查找灰度（一个版本只属于一个灰度）。 */
    Optional<GrayRelease> findByIndexVersion(int indexVersionNumber);

    List<GrayRelease> findByStatus(GrayReleaseStatus status);

    /** 返回给定知识库中已在未结束灰度里的：kbId → releaseId。 */
    Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds);

    /** 比较后更新：仅当当前状态为 expected 时改为 next 并写入 lastError，返回是否更新成功。 */
    boolean transition(long id, GrayReleaseStatus expected, GrayReleaseStatus next, String lastError);

    void rename(long id, String name);

    /** 仅当当前为 SYNCED 时进入 SWITCHED 并记录切换时间，返回是否更新成功。 */
    boolean markSwitched(long id);

    /** 仅当当前状态为 expected 时进入 ENDED、记录结束时间并释放 active_kb_id，返回是否更新成功。 */
    boolean end(long id, GrayReleaseStatus expected);

    /** 所有 SWITCHED 灰度的「物理索引名 → 解析器 → 知识库」。 */
    List<SwitchedRoute> switchedRoutes();

    record SwitchedRoute(String physicalName, String parserVersion, long kbId) { }
}
