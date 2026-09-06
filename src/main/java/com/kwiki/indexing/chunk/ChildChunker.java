package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic child chunker within one parent: paragraphs stay intact and
 * accumulate while they fit the 128-512 range; an oversized paragraph splits on
 * sentence boundaries first and only hard-cuts at the maximum when no sentence
 * boundary exists. Every child references exactly one parent key.
 */
@Component
public class ChildChunker {

    /** CJK terminators split immediately; Latin ones split on trailing whitespace. */
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
        // a too-small tail merges into the previous child when it fits
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
                // sentence ranges include their terminating punctuation
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
