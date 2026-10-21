package com.kwiki.infrastructure.ai.registry;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 由目录取值构建具体客户端；构建本身不访问网络（输出上限探测除外）。 */
public interface ModelClientFactory {

    ChatModel chat(ClientSpec spec);

    StreamingChatModel streaming(ClientSpec spec);

    /** batchSize 为每批条数；parameters 为透传给 /embeddings 请求体的额外参数。 */
    ChunkEmbeddingPort embedding(String baseUrl, String apiKey, Duration timeout, String modelName,
                                 int dimensions, int maxRetries, int batchSize, Map<String, Object> parameters);

    /** 带 provider 名的构建（用于用量归属）；默认忽略 provider 名，委托旧方法。 */
    default ChunkEmbeddingPort embedding(String providerName, String baseUrl, String apiKey, Duration timeout,
                                         String modelName, int dimensions, int maxRetries, int batchSize,
                                         Map<String, Object> parameters) {
        return embedding(baseUrl, apiKey, timeout, modelName, dimensions, maxRetries, batchSize, parameters);
    }

    /** 从 provider 的模型元数据探测输出上限；拿不到时返回 null（请求省略 max_tokens）。 */
    Integer probeOutputLimit(String baseUrl, String apiKey, Duration timeout, String modelName);

    /** GET /models 返回的模型 id；失败时抛出异常（调用方负责脱敏）。 */
    List<String> listModels(String baseUrl, String apiKey, Duration timeout);
}
