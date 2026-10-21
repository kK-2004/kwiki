package com.kwiki.infrastructure.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.eval.EvalReportPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只在提供 Langfuse 凭据时运行：创建冒烟数据集、条目并上报一条 trace。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_LANGFUSE_PUBLIC_KEY", matches = ".+")
class LangfuseRestClientLiveTest {

    @Test
    void uploadsDatasetItemAndTrace() {
        var client = new LangfuseRestClient(
                System.getenv().getOrDefault("KWIKI_IT_LANGFUSE_HOST", "https://cloud.langfuse.com"),
                System.getenv("KWIKI_IT_LANGFUSE_PUBLIC_KEY"),
                System.getenv("KWIKI_IT_LANGFUSE_SECRET_KEY"),
                new ObjectMapper(), 90);
        String dataset = "kwiki-rag-smoke";
        String itemId = dataset + ":smoke-1";
        client.upsertDataset(dataset, "kwiki 冒烟测试");
        client.upsertItem(dataset, itemId, Map.of("query", "冒烟问题"),
                Map.of("keywords", List.of(List.of("冒烟"))), Map.of("tier", 50));
        Instant now = Instant.now();
        client.recordTrace(new EvalReportPort.TraceRecord(UUID.randomUUID().toString(), "eval:smoke-1",
                Map.of("query", "冒烟问题"), Map.of("answer", "冒烟回答"), Map.of(), List.of("rag-eval", "smoke"),
                List.of(new EvalReportPort.SpanRecord(UUID.randomUUID().toString(), "answer", now, now,
                        Map.of("query", "冒烟问题"), Map.of("matched", List.of("冒烟")))),
                List.of(new EvalReportPort.ScoreRecord("coverage@answer", 1.0, null)),
                itemId, "smoke-" + now.toEpochMilli(), Map.of("notes", "smoke")));
        client.flush();
    }
}
