package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个父分块内的确定性子分块器：段落保持完整，在 128-512 区间内累积；
 * 超长段落优先在句边界切分，仅在不存在句子边界时才在最大尺寸处硬切。
 * 受保护资源块（KWIKI_META_DATA 标记）是不可切分单元：绝不做句切/
 * 硬切；单独超过上限时完整发出并计入超限指标。每个子分块都精确
 * 引用一个父分块键。
 */
@Component
public class ChildChunker {

    /** CJK 终止符立即切分；拉丁终止符在尾部空白处切分。 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "[。！？!?；;…]+|(?<=[.!?])\\s+");

    private final ChunkingConfig config;
    private final com.kwiki.indexing.multimodal.MultimodalMetrics metrics;

    public ChildChunker() {
        this(ChunkingConfig.defaults(), null);
    }

    public ChildChunker(ChunkingConfig config) {
        this(config, null);
    }

    public ChildChunker(ChunkingConfig config,
                        com.kwiki.indexing.multimodal.MultimodalMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    public List<ChildChunk> chunk(ParentChunk parent, StructuredDocument document) {
        List<StructBlock> blocks = blocksWithin(document, parent);
        List<ChildChunk> children = new ArrayList<>();
        List<StructBlock> current = new ArrayList<>();
        int currentLength = 0;

        for (StructBlock block : blocks) {
            int blockLength = block.text().length();
            if (blockLength > config.childMaxChars()) {
                if (block.isProtectedResource()) {
                    // 受保护块单独超限：整体发出，绝不切分协议标记
                    flush(parent, document, current, children);
                    current = new ArrayList<>();
                    currentLength = 0;
                    if (metrics != null) {
                        metrics.oversizedProtectedBlock();
                    }
                    children.add(new ChildChunk(
                            parent.parentKey() + ":C" + children.size(), children.size(),
                            parent.parentKey(), block.charStart(), block.charEnd(),
                            block.text(), ChildChunk.BoundaryType.PARAGRAPH));
                    continue;
                }
                flush(parent, document, current, children);
                current = new ArrayList<>();
                currentLength = 0;
                splitOversized(parent, block, children);
                continue;
            }
            if (!current.isEmpty() && currentLength + blockLength > config.childMaxChars()) {
                flush(parent, document, current, children);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(block);
            currentLength += blockLength;
        }
        flush(parent, document, current, children);
        return children;
    }

    private void flush(ParentChunk parent, StructuredDocument document,
                       List<StructBlock> blocks, List<ChildChunk> children) {
        if (blocks.isEmpty()) {
            return;
        }
        // 过小的尾部在放得下时并入前一个子分块
        int start = blocks.get(0).charStart();
        int end = blocks.get(blocks.size() - 1).charEnd();
        int length = end - start;
        if (length < config.childMinChars() && !children.isEmpty()) {
            ChildChunk previous = children.get(children.size() - 1);
            if (previous.charEnd() == start
                    && previous.content().length() + length <= config.childMaxChars()) {
                children.set(children.size() - 1, new ChildChunk(
                        previous.childKey(), previous.childOrdinal(), previous.parentKey(),
                        previous.charStart(), end,
                        document.plainText().substring(previous.charStart(), end),
                        ChildChunk.BoundaryType.PARAGRAPH));
                return;
            }
        }
        children.add(new ChildChunk(
                parent.parentKey() + ":C" + children.size(), children.size(),
                parent.parentKey(), start, end,
                document.plainText().substring(start, end),
                ChildChunk.BoundaryType.PARAGRAPH));
    }

    private void splitOversized(ParentChunk parent, StructBlock block, List<ChildChunk> children) {
        String text = block.text();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(text);
        List<int[]> sentences = new ArrayList<>();
        int sentenceStart = 0;
        while (matcher.find()) {
            if (matcher.end() > sentenceStart) {
                // 句子区间包含其结尾标点
                sentences.add(new int[]{sentenceStart, matcher.end()});
            }
            sentenceStart = matcher.end();
        }
        if (sentenceStart < text.length()) {
            sentences.add(new int[]{sentenceStart, text.length()});
        }

        int currentStart = -1;
        for (int[] sentence : sentences) {
            int length = sentence[1] - sentence[0];
            if (length > config.childMaxChars()) {
                if (currentStart >= 0) {
                    emitRange(parent, block, currentStart, sentence[0],
                            ChildChunk.BoundaryType.SENTENCE, children);
                    currentStart = -1;
                }
                int offset = sentence[0];
                while (offset < sentence[1]) {
                    int stop = Math.min(offset + config.childMaxChars(), sentence[1]);
                    emitRange(parent, block, offset, stop, ChildChunk.BoundaryType.HARD, children);
                    offset = stop;
                }
                continue;
            }
            if (currentStart < 0) {
                currentStart = sentence[0];
            } else if (sentence[1] - currentStart > config.childMaxChars()) {
                emitRange(parent, block, currentStart, sentence[0],
                        ChildChunk.BoundaryType.SENTENCE, children);
                currentStart = sentence[0];
            }
        }
        if (currentStart >= 0) {
            int lastEnd = sentences.isEmpty() ? text.length()
                    : sentences.get(sentences.size() - 1)[1];
            emitRange(parent, block, currentStart, lastEnd,
                    ChildChunk.BoundaryType.SENTENCE, children);
        }
    }

    private void emitRange(ParentChunk parent, StructBlock block, int from, int to,
                           ChildChunk.BoundaryType boundary, List<ChildChunk> children) {
        int absoluteStart = block.charStart() + from;
        int absoluteEnd = block.charStart() + to;
        children.add(new ChildChunk(
                parent.parentKey() + ":C" + children.size(), children.size(),
                parent.parentKey(), absoluteStart, absoluteEnd,
                block.text().substring(from, to), boundary));
    }

    private List<StructBlock> blocksWithin(StructuredDocument document, ParentChunk parent) {
        List<StructBlock> within = new ArrayList<>();
        for (StructBlock block : document.blocks()) {
            if (block.charStart() >= parent.charStart() && block.charEnd() <= parent.charEnd()) {
                within.add(block);
            }
        }
        return within;
    }
}
