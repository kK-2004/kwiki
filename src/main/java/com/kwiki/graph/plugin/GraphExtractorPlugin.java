package com.kwiki.graph.plugin;

import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.indexing.plugin.IndexPlugin;

/** 图构建抽取环节插件：把单个来源块交给模型抽取实体与关系。 */
public interface GraphExtractorPlugin extends IndexPlugin {

    /** 抽取提示词版本，与插件 id 一起构成抽取缓存身份。 */
    String promptVersion();

    GraphExtractionResult extract(GraphExtractionInput input);
}
