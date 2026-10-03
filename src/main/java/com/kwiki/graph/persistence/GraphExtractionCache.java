package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphSourceChunk;

import java.util.Optional;

/** 抽取结果缓存的读取与写回；行由 GraphExtractionJobPlanner.plan 预先创建。 */
public interface GraphExtractionCache {

    /** 身份一致且 READY 的缓存结果；不存在或非 READY 返回 empty。 */
    Optional<GraphExtractionResult> findReady(GraphSourceChunk source, String extractorVersion,
                                              String promptVersion, String entityLinkingVersion);

    /** 写回结果并置 READY。 */
    void markReady(long extractionId, GraphExtractionResult result);

    /** 记录最终失败原因（重试耗尽或永久失败时调用）。 */
    void markFailed(long extractionId, String errorClass, String errorSummary);
}
