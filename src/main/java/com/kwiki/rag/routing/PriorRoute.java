package com.kwiki.rag.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 上一轮的最终路由状态：落在 chat_run，供下一轮判定冲突。 */
public record PriorRoute(Intent intent, RouteSource source, boolean needsRetrieval, double confidence) {

    public PriorRoute {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(source, "source");
    }

    public static PriorRoute of(RetrievalPlan plan) {
        return new PriorRoute(plan.intent(), plan.source(), plan.needsRetrieval(), plan.confidence());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("intent", intent.name());
        map.put("source", source.name());
        map.put("needsRetrieval", needsRetrieval);
        map.put("confidence", confidence);
        return map;
    }

    /** 容错解析：缺字段或枚举非法时返回 null，调用方按「无上一轮路由」处理。 */
    public static PriorRoute fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        try {
            Intent intent = Intent.valueOf(String.valueOf(map.get("intent")));
            RouteSource source = RouteSource.valueOf(String.valueOf(map.get("source")));
            Object needs = map.get("needsRetrieval");
            Object confidence = map.get("confidence");
            return new PriorRoute(intent, source, Boolean.TRUE.equals(needs),
                    confidence instanceof Number number ? number.doubleValue() : 0.0);
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
