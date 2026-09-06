package com.kwiki.indexing.chunk;

import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic parent chunker over heading sections. Sections below the minimum
 * merge with neighbors without crossing a superior heading; sections above the
 * maximum split on subordinate headings first, then paragraphs, and only hard-cut
 * when no natural boundary exists. The same input plus key prefix always yields
 * the same parents, keys, ordinals, and boundaries.
 */
@Component
public class ParentChunker {

    private final ChunkingConfig config;

    public ParentChunker() {
        this(ChunkingConfig.defaults());
    }

    public ParentChunker(ChunkingConfig config) {
        this.config = config;
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

    /** Merges adjacent sections up to the minimum without crossing superior headings. */
    private List<Section> mergeShortSections(List<Section> sections) {
        List<Section> result = new ArrayList<>();
        Section group = null;
        for (Section section : sections) {
            if (group == null) {
                group = section;
                continue;
            }
            // merging only allowed into deeper sections: a superior heading starts a new parent
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
        // oversized: accumulate paragraphs (and subordinate-heading boundaries) up to max
        emitByAccumulation(section, plainText, keyPrefix, parents);
    }

    private void emitByAccumulation(Section section, String plainText, String keyPrefix,
                                    List<ParentChunk> parents) {
        List<StructBlock> current = new ArrayList<>();
        int currentLength = 0;
        for (StructBlock block : section.blocks()) {
            int blockLength = block.text().length();
            // a heading-only accumulator never flushes: a parent must carry body text
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
        // contiguous range exceeding the maximum: hard cut into max-size windows
        if (end - start > config.parentMaxChars()) {
            int offset = start;
            while (offset < end) {
                int stop = Math.min(offset + config.parentMaxChars(), end);
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

    private ParentChunk.BoundaryType boundaryOf(Section section) {
        return section.topLevel() > 0
                ? ParentChunk.BoundaryType.HEADING
                : ParentChunk.BoundaryType.PARAGRAPH;
    }
}
