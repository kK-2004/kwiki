package com.kwiki.indexing.multimodal;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 受保护元数据块的规范协议。每个图片位置恰好编码为一个成对块：
 *
 * <pre>
 * &lt;&lt;KWIKI_META_DATA_START {"type":"image","contentId":12345}&gt;&gt;
 * 图片的 LLM 摘要
 * &lt;&lt;KWIKI_META_DATA_END {"type":"image","contentId":12345}&gt;&gt;
 * </pre>
 *
 * <p>元数据 JSON 只允许 {@code type} 与 {@code contentId} 两个字段
 * （紧凑序列化、键序固定）；MIME、URL、源文档 id、页码、哈希一律
 * 不进入标记。解析使用 JSON 反序列化而非字段顺序正则；畸形、不配对、
 * 超长、嵌套标记与额外字段（包括 mime）都是显式失败，绝不会被当作
 * 普通用户文本或可回显资源。普通文档中碰巧出现的同名片段只有在
 * 完整通过协议校验时才具备资源语义。</p>
 */
public final class ProtectedBlockProtocol {

    public static final String TYPE_IMAGE = "image";
    public static final String START_PREFIX = "<<KWIKI_META_DATA_START ";
    public static final String START_SUFFIX = ">>";
    public static final String END_PREFIX = "<<KWIKI_META_DATA_END ";
    public static final String END_SUFFIX = ">>";

    /** 元数据行（含前后缀）的长度上限；超过即畸形。 */
    public static final int MAX_METADATA_LINE_CHARS = 256;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();

    private final int maxSummaryChars;

    public ProtectedBlockProtocol(int maxSummaryChars) {
        if (maxSummaryChars < 16) {
            throw new IllegalArgumentException("max summary chars must be >= 16");
        }
        this.maxSummaryChars = maxSummaryChars;
    }

    /** 受保护块携带的唯一资源身份：类型 + 正整数 contentId。 */
    public record ResourceRef(String type, long contentId) {

        public ResourceRef {
            if (!TYPE_IMAGE.equals(type) || contentId <= 0) {
                throw new IllegalArgumentException(
                        "only type=image with a positive contentId is representable");
            }
        }

        public static ResourceRef image(long contentId) {
            return new ResourceRef(TYPE_IMAGE, contentId);
        }
    }

    /** 一段已解析的受保护块：在文本中的区间、身份与摘要正文。 */
    public record ParsedBlock(int start, int end, ResourceRef ref, String summary) {
    }

    /** 协议违规：调用方必须显式失败，绝不可降级为普通文本。 */
    public static final class ProtocolViolationException extends RuntimeException {
        public ProtocolViolationException(String message) {
            super(message);
        }
    }

    /** 规范序列化：紧凑 JSON、固定键序、START/END 身份一致。 */
    public String serialize(ResourceRef ref, String summary) {
        Objects.requireNonNull(ref, "ref");
        String normalized = requireValidSummary(summary);
        String metadata = metadataJson(ref);
        return START_PREFIX + metadata + START_SUFFIX + "\n"
                + normalized + "\n"
                + END_PREFIX + metadata + END_SUFFIX;
    }

    /** 受保护块的规范纯文本（供 StructBlock 使用）。 */
    public String blockText(ResourceRef ref, String summary) {
        return serialize(ref, summary);
    }

    /**
     * 扫描完整文档文本中的全部成对受保护块（严格模式）。
     * 任何协议违规（含嵌套标记、不配对 START）都抛出
     * {@link ProtocolViolationException}——本模式只用于
     * 索引流水线自己组装（并做过用户文本中和）的文档。
     */
    public List<ParsedBlock> scan(String text) {
        List<ParsedBlock> blocks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return blocks;
        }
        int search = 0;
        while (true) {
            int startMarker = text.indexOf(START_PREFIX, search);
            if (startMarker < 0) {
                break;
            }
            Candidate candidate = tryParseAt(text, startMarker);
            if (candidate.error() != null) {
                throw new ProtocolViolationException(candidate.error());
            }
            blocks.add(candidate.block());
            search = candidate.block().end();
        }
        return blocks;
    }

    /**
     * 宽松扫描：只有完整通过协议校验的成对块才被识别；
     * 碰巧包含标记片段的普通文本被跳过（不具备资源语义，
     * 也不报错）。用于分块投影、embedding 投影等可能接触
     * 历史文档自由文本的场景。
     */
    public List<ParsedBlock> scanLenient(String text) {
        List<ParsedBlock> blocks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return blocks;
        }
        int search = 0;
        while (true) {
            int startMarker = text.indexOf(START_PREFIX, search);
            if (startMarker < 0) {
                break;
            }
            Candidate candidate = tryParseAt(text, startMarker);
            if (candidate.error() != null) {
                // 跳过这个片段形态的伪标记，继续寻找下一个 START
                search = startMarker + START_PREFIX.length();
                continue;
            }
            blocks.add(candidate.block());
            search = candidate.block().end();
        }
        return blocks;
    }

    /** 单个候选块的解析结果：block 与 error 互斥。 */
    private record Candidate(ParsedBlock block, String error) {

        static Candidate ok(ParsedBlock block) {
            return new Candidate(block, null);
        }

        static Candidate invalid(String error) {
            return new Candidate(null, error);
        }
    }

    /** 在 startMarker 处尝试解析一个完整受保护块。 */
    private Candidate tryParseAt(String text, int startMarker) {
        int metadataEnd = text.indexOf(START_SUFFIX, startMarker + START_PREFIX.length());
        if (metadataEnd < 0) {
            return Candidate.invalid("unterminated START marker");
        }
        String startMetadata = text.substring(startMarker + START_PREFIX.length(), metadataEnd);
        int contentStart = metadataEnd + START_SUFFIX.length();
        if (contentStart < text.length() && text.charAt(contentStart) == '\r') {
            contentStart++;
        }
        if (contentStart >= text.length() || text.charAt(contentStart) != '\n') {
            return Candidate.invalid("START marker must end its line");
        }
        contentStart++;

        int endMarker = text.indexOf(END_PREFIX, contentStart);
        if (endMarker < 0) {
            return Candidate.invalid("START marker without matching END marker");
        }
        String summary = text.substring(contentStart, endMarker);
        if (summary.endsWith("\n")) {
            summary = summary.substring(0, summary.length() - 1);
        }
        int endMetadataClose = text.indexOf(END_SUFFIX, endMarker + END_PREFIX.length());
        if (endMetadataClose < 0) {
            return Candidate.invalid("unterminated END marker");
        }
        String endMetadata = text.substring(endMarker + END_PREFIX.length(), endMetadataClose);
        int blockEnd = endMetadataClose + END_SUFFIX.length();

        ResourceRef startRef;
        ResourceRef endRef;
        try {
            startRef = parseMetadata(startMetadata);
            endRef = parseMetadata(endMetadata);
        } catch (ProtocolViolationException violation) {
            return Candidate.invalid(violation.getMessage());
        }
        if (!startRef.equals(endRef)) {
            return Candidate.invalid("START and END metadata disagree on resource identity");
        }
        // 摘要正文中绝不允许再出现任何标记前缀：嵌套即违规。
        if (summary.contains(START_PREFIX) || summary.contains(END_PREFIX)) {
            return Candidate.invalid("nested markers inside a protected block");
        }
        try {
            requireValidSummary(summary);
        } catch (ProtocolViolationException violation) {
            return Candidate.invalid(violation.getMessage());
        }
        return Candidate.ok(new ParsedBlock(startMarker, blockEnd, startRef, summary));
    }

    /** 校验单个摘要文本：非空、去首尾空白后非空、限长、无标记。 */
    public String requireValidSummary(String summary) {
        if (summary == null) {
            throw new ProtocolViolationException("protected block summary is null");
        }
        String normalized = summary.strip();
        if (normalized.isEmpty()) {
            throw new ProtocolViolationException("protected block summary is blank");
        }
        if (normalized.contains(START_PREFIX) || normalized.contains(END_PREFIX)) {
            throw new ProtocolViolationException("summary must not contain marker syntax");
        }
        if (normalized.length() > maxSummaryChars) {
            throw new ProtocolViolationException(
                    "protected block summary exceeds " + maxSummaryChars + " chars");
        }
        return normalized;
    }

    /**
     * embedding/检索上下文投影：去掉 START/END 包装行，仅保留摘要。
     * 宽松模式——不含有效受保护块的文本（含历史文档中的标记
     * 形态片段）原样返回。
     */
    public String stripMarkers(String text) {
        if (text == null || text.isEmpty()
                || !text.contains(START_PREFIX) || !text.contains(END_PREFIX)) {
            return text == null ? "" : text;
        }
        List<ParsedBlock> blocks = scanLenient(text);
        StringBuilder projection = new StringBuilder();
        int cursor = 0;
        for (ParsedBlock block : blocks) {
            projection.append(text, cursor, block.start());
            projection.append(block.summary());
            cursor = block.end();
        }
        projection.append(text.substring(cursor));
        return projection.toString();
    }

    /** 提取文本中全部资源身份（按出现顺序去重，宽松模式）。 */
    public List<ResourceRef> resourceRefsDistinct(String text) {
        List<ResourceRef> refs = new ArrayList<>();
        for (ParsedBlock block : scanLenient(text)) {
            if (!refs.contains(block.ref())) {
                refs.add(block.ref());
            }
        }
        return refs;
    }

    /**
     * 中和用户文本中的标记片段：把标记前缀的首个 "&lt;" 折叠掉，
     * 使其永远无法组成（或破坏）协议块。组装投影前对所有
     * 用户来源文本执行本变换，保证"文档中出现的任何标记都由
     * 索引流水线生成"这一扫描前提成立；变换只影响索引投影，
     * 不触碰数据库中的页面修订原文。
     */
    public String sanitizeUserText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace(START_PREFIX, "<KWIKI_META_DATA_START ")
                .replace(END_PREFIX, "<KWIKI_META_DATA_END ");
    }

    /** 解析并校验一段元数据 JSON：仅 type/contentId，其余任何字段违规。 */
    private ResourceRef parseMetadata(String metadata) {
        if (metadata.length() + START_PREFIX.length() > MAX_METADATA_LINE_CHARS) {
            throw new ProtocolViolationException("metadata line exceeds the protocol limit");
        }
        MetadataJson parsed;
        try {
            parsed = MAPPER.readValue(metadata, MetadataJson.class);
        } catch (UnrecognizedPropertyException extra) {
            throw new ProtocolViolationException(
                    "metadata contains a field outside the protocol: " + extra.getPropertyName());
        } catch (Exception malformed) {
            throw new ProtocolViolationException("metadata is not valid protocol JSON");
        }
        if (!TYPE_IMAGE.equals(parsed.type())) {
            throw new ProtocolViolationException("metadata type must be image");
        }
        if (parsed.contentId() == null || parsed.contentId() <= 0) {
            throw new ProtocolViolationException("metadata contentId must be a positive integer");
        }
        return new ResourceRef(parsed.type(), parsed.contentId());
    }

    private String metadataJson(ResourceRef ref) {
        // 固定键序的紧凑序列化；type 已由 ResourceRef 限定为 image
        String json = "{\"type\":\"" + TYPE_IMAGE + "\",\"contentId\":" + ref.contentId() + "}";
        if (json.length() + START_PREFIX.length() > MAX_METADATA_LINE_CHARS) {
            throw new ProtocolViolationException("metadata line exceeds the protocol limit");
        }
        return json;
    }

    /**
     * Jackson 严格绑定记录：未知字段（mime、url、hash……）在默认
     * FAIL_ON_UNKNOWN_PROPERTIES 下直接失败；标量强转（"123" 字符串
     * contentId）已被禁用。
     */
    record MetadataJson(String type, Long contentId) {
    }
}
