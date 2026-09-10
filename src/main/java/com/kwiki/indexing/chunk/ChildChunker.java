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
 * 每个子分块都精确引用一个父分块键。
 */
@Component
public class ChildChunker {

    /** CJK 终止符立即切分；拉丁终止符在尾部空白处切分。 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "[。！？!?；;…]+|(?<=[.!?])\\s+");

    private final ChunkingConfig config;

    public ChildChunker() {
        this(ChunkingConfig.defaults());
    }

    public ChildChunker(ChunkingConfig config) {
        this.config = config;
    }

    public List<ChildChunk> chunk(ParentChunk parent, StructuredDocument document) {
        List<StructBlock> blocks = blocksWithin(document, parent);
        List<ChildChunk> children = new ArrayList<>();
        List<StructBlock> current = new ArrayList<>();
        int currentLength = 0;

        for (StructBlock block : blocks) {
            int blockLength = block.text().length();
            if (blockLength > config.childMaxChars()) {
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
