package com.kwiki.indexing.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读写分离回归（任务 4.1）：全部 BM25/向量/父块/引用读取路径只经
 * kwiki-chunks 别名；任何读取适配器都不得命名影子（物理版本）索引。
 * 反向约束（写入必须显式物理名）由 ChunkIndexRepositoryTest 的
 * requirePhysicalIndex 契约保证。
 */
class ReadAliasSeparationTest {

    private static final List<String> READ_ADAPTERS = List.of(
            "src/main/java/com/kwiki/infrastructure/elasticsearch/VectorRecallAdapter.java",
            "src/main/java/com/kwiki/infrastructure/elasticsearch/Bm25RecallAdapter.java",
            "src/main/java/com/kwiki/infrastructure/elasticsearch/EsParentChunkFetcher.java",
            "src/main/java/com/kwiki/infrastructure/elasticsearch/EsChunkLookup.java");

    @Test
    void everyReadAdapterReadsThroughTheStableAliasOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String adapter : READ_ADAPTERS) {
            assertThat(Files.exists(Path.of(adapter))).as("%s exists", adapter).isTrue();
            for (String line : Files.readAllLines(Path.of(adapter))) {
                if (line.contains("kwiki-chunks-v")) {
                    violations.add(adapter + " names a physical index: " + line.trim());
                }
            }
            String source = Files.readString(Path.of(adapter));
            if (!source.contains("ElasticsearchIndexManager.ALIAS")) {
                violations.add(adapter + " does not read through ElasticsearchIndexManager.ALIAS");
            }
        }
        assertThat(violations)
                .as("retrieval reads must stay on the kwiki-chunks alias")
                .isEmpty();
    }

    @Test
    void writePathClassesNeverTargetTheAliasInProductionSources() throws IOException {
        // 写入侧（worker/入队/ES 写适配器/归档同步）不得出现以别名为
        // 写目标的调用字面量；物理名校验在运行时由 requirePhysicalIndex
        // 再守一道。
        List<String> writePackages = List.of(
                "src/main/java/com/kwiki/indexing/job",
                "src/main/java/com/kwiki/indexing/search/ChunkIndexRepository.java",
                "src/main/java/com/kwiki/wiki/archive");
        List<String> violations = new ArrayList<>();
        for (String root : writePackages) {
            try (Stream<Path> paths = Files.walk(Path.of(root))) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    try {
                        for (String line : Files.readAllLines(path)) {
                            if (line.contains("\"kwiki-chunks\"")) {
                                violations.add(path + " names the read alias as a literal: "
                                        + line.trim());
                            }
                        }
                    } catch (IOException unreadable) {
                        violations.add(path + " (unreadable)");
                    }
                });
            }
        }
        assertThat(violations)
                .as("write paths must name explicit physical indexes, never the alias literal")
                .isEmpty();
    }
}
