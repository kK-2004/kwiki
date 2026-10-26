package com.kwiki.rag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户在 Wiki 页面划线后带入对话的一段引用。请求里只信任 kbId、pageId 与文本，
 * 页面标题与所在段落由服务端按当前用户的读权限重新解析（见 {@link ChatQuoteResolver}）。
 *
 * <p>引用不改变路由与工具选择：它被拼进送给工作流的有效问题，作为上下文参与意图识别、
 * 改写与规划；入库的用户消息仍是用户原话，引用单独存放在 {@code chat_message.quote_json}。</p>
 */
public record ChatQuote(Long kbId, Long pageId, String pageTitle, String text, String paragraph) {

    public static final int MAX_TEXT_CHARS = 4000;
    /** 所在段落只作补充上下文，过长则不带，避免挤占检索与改写的输入预算。 */
    public static final int MAX_PARAGRAPH_CHARS = 1500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 请求体里的引用：只有定位信息与划线文本。 */
    public static ChatQuote request(Long kbId, Long pageId, String text) {
        return new ChatQuote(kbId, pageId, null, text, null);
    }

    /** 送给工作流的有效问题：引用在前、用户问题在后。 */
    public String compose(String query) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("我在页面《").append(pageTitle == null ? "" : pageTitle).append("》中划线引用了这段内容：\n「")
                .append(text).append("」\n");
        if (paragraph != null && !paragraph.isBlank() && !paragraph.trim().equals(text)) {
            prompt.append("它所在的段落：\n").append(paragraph.trim()).append('\n');
        }
        return prompt.append("我的问题：").append(query).toString();
    }

    /** 多轮上下文里的历史提问：只带引用文本，不带段落，保持改写上下文紧凑。 */
    public String contextLine(String query) {
        return "「" + text + "」" + query;
    }

    /** 入库与回显用的 JSON；段落不入库，回放时不需要。 */
    public String toJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kbId", kbId);
        value.put("pageId", pageId);
        value.put("pageTitle", pageTitle);
        value.put("text", text);
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 解析入库的 JSON；为空或格式不对时返回 null，不影响历史加载。 */
    public static ChatQuote fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(json);
            String text = node.path("text").asText("");
            if (text.isBlank()) return null;
            return new ChatQuote(node.hasNonNull("kbId") ? node.get("kbId").asLong() : null,
                    node.hasNonNull("pageId") ? node.get("pageId").asLong() : null,
                    node.hasNonNull("pageTitle") ? node.get("pageTitle").asText() : null, text, null);
        } catch (Exception malformed) {
            return null;
        }
    }

    /** 历史回显给前端的视图：不含段落。 */
    public Map<String, Object> view() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kbId", kbId);
        value.put("pageId", pageId);
        value.put("pageTitle", pageTitle);
        value.put("text", text);
        return value;
    }
}
