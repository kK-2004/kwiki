package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于标题层级的确定性父分块器。低于最小长度的章节
 * 与相邻章节合并，且不跨越更上级标题；超过最大长度的章节
 * 先按下级标题切分，再按段落切分，只有在不存在自然
 * 边界时才硬切。受保护资源块（KWIKI_META_DATA 标记）是
 * 不可切分单元：任何切点都会被移出标记区间；单独超过上限的
 * 受保护块整体发出（超限计数计入指标）而非截断协议。
 * 相同的输入加上 key 前缀总是产出相同的父分块、key、序号与边界。
 */
@Component
public class ParentChunker {

    private final ChunkingConfig config;
    private final com.kwiki.indexing.multimodal.MultimodalMetrics metrics;

    public ParentChunker() {
        this(ChunkingConfig.defaults(), null);
    }

    public ParentChunker(ChunkingConfig config) {
        this(config, null);
    }

    public ParentChunker(ChunkingConfig config,
                         com.kwiki.indexing.multimodal.MultimodalMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    public List<ParentChunk> chunk(String keyPrefix, StructuredDocument document) {
        List<Section> sections = sectionize(document);
        List<Section> merged = mergeShortSections(sections);
        List<ParentChunk> parents = new ArrayList<>();
        for (Section section : merged) {
            emit(section, document.plainText(), keyPrefix, parents);
        }
        return parents;
    }

    private record Section(int topLevel, List<String> headingPath, List<StructBlock> blocks) {
        int length() {
            return blocks.stream().mapToInt(block -> block.text().length()).sum();
        }

        int start() {
            return blocks.get(0).charStart();
        }

        int end() {
            return blocks.get(blocks.size() - 1).charEnd();
        }
    }

    private List<Section> sectionize(StructuredDocument document) {
        List<Section> sections = new ArrayList<>();
        List<String> path = new ArrayList<>();
        Section current = new Section(0, new ArrayList<>(), new ArrayList<>());
        for (StructBlock block : document.blocks()) {
            if (block.isHeading()) {
                if (!current.blocks().isEmpty()) {
                    sections.add(current);
                }
                List<String> newPath = new ArrayList<>(path);
                while (!newPath.isEmpty()
                        && newPath.size() >= block.headingLevel()) {
                    newPath.remove(newPath.size() - 1);
                }
                newPath.add(block.text());
                path = new ArrayList<>(newPath);
                current = new Section(block.headingLevel(), newPath, new ArrayList<>(List.of(block)));
            } else {
                current.blocks().add(block);
            }
        }
        if (!current.blocks().isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    /** 将相邻章节合并至最小长度，且不跨越更上级标题。 */
    private List<Section> mergeShortSections(List<Section> sections) {
        List<Section> result = new ArrayList<>();
        Section group = null;
        for (Section section : sections) {
            if (group == null) {
                group = section;
                continue;
            }
            // 只允许并入更深的章节：遇到更上级标题即开启新的父分块
            boolean deeper = section.topLevel() > group.topLevel();
            boolean fits = group.length() + section.length() < config.parentMinChars();
            if (deeper && fits) {
                List<StructBlock> combined = new ArrayList<>(group.blocks());
                combined.addAll(section.blocks());
                group = new Section(group.topLevel(), group.headingPath(), combined);
            } else {
                result.add(group);
                group = section;
            }
        }
        if (group != null) {
            result.add(group);
        }
        return result;
    }

    private void emit(Section section, String plainText, String keyPrefix,
                      List<ParentChunk> parents) {
        int length = section.length();
        if (length <= config.parentMaxChars()) {
            parents.add(new ParentChunk(
                    keyPrefix + ":P" + parents.size(), parents.size(),
                    section.headingPath(), section.start(), section.end(),
                    plainText.substring(section.start(), section.end()),
                    boundaryOf(section)));
            return;
        }
        // 超长时：累积段落（以及下级标题边界）直至上限
        emitByAccumulation(section, plainText, keyPrefix, parents);
    }

    private void emitByAccumulation(Section section, String plainText, String keyPrefix,
                                    List<ParentChunk> parents) {
        List<StructBlock> current = new ArrayList<>();
        int currentLength = 0;
        for (StructBlock block : section.blocks()) {
            int blockLength = block.text().length();
            // 只有标题的累加器永不落地：父分块必须携带正文文本
            boolean onlyHeadings = current.stream().allMatch(StructBlock::isHeading);
            if (!current.isEmpty() && !onlyHeadings
                    && currentLength + blockLength > config.parentMaxChars()) {
                emitRange(section, plainText, keyPrefix, parents, current);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(block);
            currentLength += blockLength;
        }
        if (!current.isEmpty()) {
            emitRange(section, plainText, keyPrefix, parents, current);
        }
    }

    private void emitRange(Section section, String plainText, String keyPrefix,
                           List<ParentChunk> parents, List<StructBlock> blocks) {
        int start = blocks.get(0).charStart();
        int end = blocks.get(blocks.size() - 1).charEnd();
        // 连续区间超出最大尺寸：按最大尺寸的窗口硬切；
        // 切点绝不落入受保护块内部（任务 7.3 原子性）。
        if (end - start > config.parentMaxChars()) {
            int offset = start;
            while (offset < end) {
                int stop = Math.min(offset + config.parentMaxChars(), end);
                stop = adjustCutAroundProtectedBlocks(blocks, offset, stop);
                if (stop <= offset) {
                    // 单个受保护块独占超限 chunk：完整发出并计数
                    recordOversizedProtectedBlock();
                    stop = endOfProtectedBlockContaining(blocks, offset);
                }
                parents.add(new ParentChunk(
                        keyPrefix + ":P" + parents.size(), parents.size(),
                        section.headingPath(), offset, stop,
                        plainText.substring(offset, stop), ParentChunk.BoundaryType.HARD));
                offset = stop;
            }
            return;
        }
        parents.add(new ParentChunk(
                keyPrefix + ":P" + parents.size(), parents.size(),
                section.headingPath(), start, end,
                plainText.substring(start, end),
                blocks.get(0).isHeading()
                        ? ParentChunk.BoundaryType.HEADING
                        : ParentChunk.BoundaryType.PARAGRAPH));
    }

    /**
     * 若 stop 严格落在受保护块内部，把切点前移到该块起点；
     * 块起点等于 offset（无处可退）时返回 offset-1，由调用方
     * 以超限 chunk 形式完整发出该块。
     */
    private static int adjustCutAroundProtectedBlocks(List<StructBlock> blocks,
                                                      int offset, int stop) {
        for (StructBlock block : blocks) {
            if (!block.isProtectedResource()) {
                continue;
            }
            if (stop > block.charStart() && stop < block.charEnd()) {
                return block.charStart() > offset ? block.charStart() : offset - 1;
            }
        }
        return stop;
    }

    private static int endOfProtectedBlockContaining(List<StructBlock> blocks, int offset) {
        for (StructBlock block : blocks) {
            if (block.isProtectedResource()
                    && offset >= block.charStart() && offset < block.charEnd()) {
                return block.charEnd();
            }
        }
        return offset; // 不可达：调用方仅在包含受保护块时进入该分支
    }

    private void recordOversizedProtectedBlock() {
        if (metrics != null) {
            metrics.oversizedProtectedBlock();
        }
    }

    private ParentChunk.BoundaryType boundaryOf(Section section) {
        return section.topLevel() > 0
                ? ParentChunk.BoundaryType.HEADING
                : ParentChunk.BoundaryType.PARAGRAPH;
    }
}
