package com.kwiki.graph.plugin;

import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.indexing.plugin.IndexPlugin;

/** 图构建社区检测环节插件：在无权实体投影上执行社区发现。 */
public interface CommunityDetectorPlugin extends IndexPlugin {

    CommunityDetectionResult detect(CommunityDetectionInput input);
}
