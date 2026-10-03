package com.kwiki.graph.build.stage;

import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;

/** 图构建执行阶段；各阶段实现按状态机顺序由 GraphBuildRunner 调度。 */
public interface GraphBuildStage {

    void execute(RunContext ctx, RunArtifacts artifacts);
}
