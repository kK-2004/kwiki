package com.kwiki.rag.answer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 待答反问的存储形态（chat_run.pending_clarification）。回答到达时以服务端存储的
 * 选项为准校验，选项标签与范围不信任客户端。
 */
public record PendingClarification(String id, String originalQuery, String source,
                                   boolean allowFreeText, List<Option> options) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 合成后的有效问题不得超过工作流的 1000 字符上限：原问题与回答各取 480。 */
    private static final int PART_LIMIT = 480;

    public record Option(String id, String label, Long kbId, Long pageId) {
    }

    /** 校验通过的回答：合成后的有效问题与选项带来的范围。 */
    public record Resolved(String effectiveQuery, Set<Long> kbIds, Set<Long> pageIds) {

        /** 选项带范围时收窄请求范围（交集；无交集则取选项范围）；不带范围则保持请求范围。 */
        public Set<Long> kbScope(Set<Long> requested) {
            return narrow(requested, kbIds);
        }

        public Set<Long> pageScope(Set<Long> requested) {
            return narrow(requested, pageIds);
        }

        private static Set<Long> narrow(Set<Long> requested, Set<Long> chosen) {
            Set<Long> base = requested == null ? Set.of() : requested;
            if (chosen.isEmpty()) {
                return base;
            }
            if (base.isEmpty()) {
                return chosen;
            }
            Set<Long> intersection = new LinkedHashSet<>(base);
            intersection.retainAll(chosen);
            return intersection.isEmpty() ? chosen : intersection;
        }
    }

    public PendingClarification {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 由 clarification 事件负载构造。 */
    public static PendingClarification from(Map<String, Object> payload, String originalQuery) {
        List<Option> options = new ArrayList<>();
        if (payload.get("options") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    options.add(new Option(String.valueOf(map.get("id")), String.valueOf(map.get("label")),
                            longOrNull(map.get("kbId")), longOrNull(map.get("pageId"))));
                }
            }
        }
        return new PendingClarification(
                String.valueOf(payload.get("clarificationId")),
                originalQuery,
                String.valueOf(payload.getOrDefault("source", "router")),
                !Boolean.FALSE.equals(payload.get("allowFreeText")),
                options);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("pending clarification serialization failed", e);
        }
    }

    /** 容错解析：无效 JSON 视为没有待答反问。 */
    public static PendingClarification parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PendingClarification.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Optional<Resolved> resolve(ClarificationReply reply) {
        if (reply == null || reply.id() == null || !id.equals(reply.id())) {
            return Optional.empty();
        }
        String answer;
        Set<Long> kbIds = Set.of();
        Set<Long> pageIds = Set.of();
        if (reply.optionId() != null && !reply.optionId().isBlank()) {
            Option chosen = options.stream()
                    .filter(option -> option.id().equals(reply.optionId()))
                    .findFirst().orElse(null);
            if (chosen == null) {
                return Optional.empty();
            }
            answer = chosen.label();
            if (chosen.kbId() != null) {
                kbIds = Set.of(chosen.kbId());
            }
            if (chosen.pageId() != null) {
                pageIds = Set.of(chosen.pageId());
            }
        } else if (allowFreeText && reply.text() != null && !reply.text().isBlank()) {
            answer = reply.text().strip();
        } else {
            return Optional.empty();
        }
        String effective = bounded(originalQuery) + "\n（用户补充：" + bounded(answer) + "）";
        return Optional.of(new Resolved(effective, kbIds, pageIds));
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= PART_LIMIT ? value : value.substring(0, PART_LIMIT);
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
