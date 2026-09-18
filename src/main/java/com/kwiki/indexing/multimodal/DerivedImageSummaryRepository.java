package com.kwiki.indexing.multimodal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DerivedImageSummaryRepository extends JpaRepository<DerivedImageSummary, Long> {

    /** 唯一键 (asset_id, model, prompt_version) 的确定性查找。 */
    Optional<DerivedImageSummary> findByAssetIdAndModelAndPromptVersion(
            Long assetId, String model, String promptVersion);
}
