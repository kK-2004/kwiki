package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ES 别名切换成功后的数据库收敛；只改变读目标，写入集合完全由管理员的写入开关决定。 */
@Service
public class SearchIndexSelectionRegistry {
    private final SearchIndexVersionRepository versions;
    public SearchIndexSelectionRegistry(SearchIndexVersionRepository versions){this.versions=versions;}

    @Transactional
    public void select(int targetVersion){
        var all=versions.findAllActiveForUpdate();
        SearchIndexVersion target=all.stream().filter(v->v.getVersionNumber()==targetVersion)
                .findFirst().orElseThrow(()->new IllegalStateException("switch target disappeared"));
        if(!target.isWriteEnabled())
            throw new IllegalStateException("target version must accept writes before selection");
        for(SearchIndexVersion version:all){
            if(version.isSelected()) version.applySnapshot(IndexVersionStatusPolicy.unpublish(
                    version.toSnapshot(false)));
        }
        target.applySnapshot(IndexVersionStatusPolicy.publish(target.toSnapshot(false)));
    }
}
