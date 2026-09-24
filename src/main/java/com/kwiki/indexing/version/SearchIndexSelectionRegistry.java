package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ES 提交成功后的数据库收敛；全部非停用版本继续作为写目标。 */
@Service
public class SearchIndexSelectionRegistry {
    private final SearchIndexVersionRepository versions;
    public SearchIndexSelectionRegistry(SearchIndexVersionRepository versions){this.versions=versions;}

    @Transactional
    public void select(int targetVersion){
        var all=versions.findAllActiveForUpdate();
        SearchIndexVersion target=all.stream().filter(v->v.getVersionNumber()==targetVersion)
                .findFirst().orElseThrow(()->new IllegalStateException("switch target disappeared"));
        for(SearchIndexVersion version:all){
            if(version.isSelected()) version.applySnapshot(IndexVersionStatusPolicy.unpublish(
                    version.toSnapshot(false,false)));
            if(!version.isAdminDisabled()&&version.isPipelineSupported())
                version.enableForSwitchPreparation();
        }
        target.applySnapshot(IndexVersionStatusPolicy.publish(target.toSnapshot(false,false)));
    }
}
