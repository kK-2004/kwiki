package com.kwiki.indexing.multimodal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DerivedImageAssetRepository extends JpaRepository<DerivedImageAsset, Long> {

    /**
     * 按完整身份（源 + 解析器版本 + 图片哈希）查找权威行；
     * 数据库唯一约束保证并发插入最终只有一行可被重复读到。
     */
    Optional<DerivedImageAsset> findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
            String sourceKind, String sourceRef, String parserVersion, String imageSha256);

    List<DerivedImageAsset> findBySourceKindAndSourceRef(String sourceKind, String sourceRef);

    /** 引用同一 contentId 的全部资产行（清理候选判定用）。 */
    List<DerivedImageAsset> findByContentId(Long contentId);

    @Modifying
    @Query("update DerivedImageAsset a set a.cleanupState = 'ORPHAN_CANDIDATE' "
            + "where a.cleanupState = 'NONE' and a.id in :ids")
    int markOrphanCandidates(@Param("ids") List<Long> ids);

    long countByCleanupState(String cleanupState);

    /** 记录来源 PDF 页码；按 id 直接更新，不参与资产状态机的乐观锁。 */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update DerivedImageAsset a set a.sourcePage = :page "
            + "where a.id = :id and (a.sourcePage is null or a.sourcePage <> :page)")
    int updateSourcePage(@Param("id") long id, @Param("page") int page);
}
