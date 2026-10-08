package com.kwiki.rag.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次反问：问题文本、至多 4 个选项、是否允许自由输入。 */
public record ClarifyRequest(String question, List<Option> options, boolean allowFreeText, String source) {

    public static final int MAX_OPTIONS = 4;

    /** kbId / pageId 仅由服务端（planner 阶段）填写；router LLM 的选项不带范围。 */
    public record Option(String id, String label, Long kbId, Long pageId) {
        public Option(String id, String label) {
            this(id, label, null, null);
        }
    }

    public ClarifyRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("clarify question must be nonblank");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("too many clarify options");
        }
        if (!allowFreeText && options.isEmpty()) {
            throw new IllegalArgumentException("clarify needs options or free text");
        }
        source = source == null ? "router" : source;
    }

    /** SSE `clarification` 事件的负载。 */
    public Map<String, Object> toPayload(String clarificationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clarificationId", clarificationId);
        payload.put("question", question);
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Option option : options) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", option.id());
            item.put("label", option.label());
            if (option.kbId() != null) {
                item.put("kbId", option.kbId());
            }
            if (option.pageId() != null) {
                item.put("pageId", option.pageId());
            }
            rendered.add(item);
        }
        payload.put("options", rendered);
        payload.put("allowFreeText", allowFreeText);
        payload.put("source", source);
        return payload;
    }
}
