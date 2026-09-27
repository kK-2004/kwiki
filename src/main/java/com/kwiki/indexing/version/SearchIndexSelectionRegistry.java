package com.kwiki.indexing.version;

import com.kwiki.indexing.gray.IndexVersionKbScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ES 提交成功后的数据库收敛；全部非停用版本继续作为写目标。 */
@Service
public class SearchIndexSelectionRegistry {
    private final SearchIndexVersionRepository versions;
    public SearchIndexSelectionRegistry(SearchIndexVersionRepository versions){this.versions=versions;}

    /** 灰度版本的知识库范围；未注入（离线测试）时视为全部为全局版本。 */
    private IndexVersionKbScope kbScope;

    @Autowired(required = false)
    public void setKbScope(IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }

    @Transactional
    public void select(int targetVersion){
        var all=versions.findAllActiveForUpdate();
        SearchIndexVersion target=all.stream().filter(v->v.getVersionNumber()==targetVersion)
                .findFirst().orElseThrow(()->new IllegalStateException("switch target disappeared"));
        for(SearchIndexVersion version:all){
            if(version.isSelected()) version.applySnapshot(IndexVersionStatusPolicy.unpublish(
                    version.toSnapshot(false,false)));
            // 灰度版本的写入只由灰度自身的切换准备开启，全局选择不得顺带开启
            boolean gray=version.getVersionNumber()!=targetVersion
                    &&kbScope!=null&&kbScope.isScoped(version.getVersionNumber());
            if(!gray&&!version.isAdminDisabled()&&version.isPipelineSupported())
                version.enableForSwitchPreparation();
        }
        target.applySnapshot(IndexVersionStatusPolicy.publish(target.toSnapshot(false,false)));
    }
}
