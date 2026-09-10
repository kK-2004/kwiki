package com.kwiki.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全仓库审计：当前任何生产、构建、运行时配置、
 * 脚本或测试路径都不得再依赖 MinIO —— 即 Maven 构件、io.minio
 * 导入、kwiki.minio 配置项，或 KWIKI_MINIO_* 环境变量。
 * 有意为之的历史引用仅存在于 openspec/ 的变更历史中，本审计
 * 刻意不扫描该目录。
 */
class NoMinioCouplingAuditTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();

    /** 除单个文件外还会扫描的目录；openspec/ 不在范围内。 */
    private static final List<String> SCANNED_DIRS = List.of("src", "scripts");
    private static final List<String> SCANNED_FILES = List.of("pom.xml", ".env.example");

    private static final Pattern MINIO_REFERENCE = Pattern.compile(
            "io\\.minio|io\\s*\\.\\s*minio|minio-\\d|<artifactId>minio</artifactId>|"
                    + "kwiki\\.minio|KWIKI_MINIO_|minio\\.health|minioclient",
            Pattern.CASE_INSENSITIVE);

    /** content-center SDK 只能出现在 content-center 适配器包中。 */
    private static final Pattern SDK_IMPORT = Pattern.compile("com\\.kk\\.sdk\\.");

    @Test
    void noBuildOrSourcePathStillReferencesMinio() throws IOException {
        // 本审计文件必然要写出它所检索的模式名称。
        Path self = REPO_ROOT.resolve("src/test/java/com/kwiki/audit/NoMinioCouplingAuditTest.java");
        List<String> offenders = new ArrayList<>();
        for (String dir : SCANNED_DIRS) {
            Path path = REPO_ROOT.resolve(dir);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(path)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> !file.equals(self))
                        .filter(this::isTextFile)
                        .forEach(file -> {
                            String content = read(file);
                            if (MINIO_REFERENCE.matcher(content).find()) {
                                offenders.add(REPO_ROOT.relativize(file).toString());
                            }
                        });
            }
        }
        for (String name : SCANNED_FILES) {
            Path path = REPO_ROOT.resolve(name);
            if (Files.exists(path) && MINIO_REFERENCE.matcher(read(path)).find()) {
                offenders.add(name);
            }
        }
        assertThat(offenders)
                .as("MinIO artifact/import/config/env references must be gone; found in: %s",
                        offenders)
                .isEmpty();
    }

    @Test
    void pomDeclaresOnlyTheContentCenterStorageDependency() throws IOException {
        String pom = Files.readString(REPO_ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom)
                .as("pom must declare the content-center SDK")
                .contains("<groupId>com.kk</groupId>")
                .contains("<artifactId>content-center-sdk</artifactId>")
                .contains("<content-center-sdk.version>0.1.3</content-center-sdk.version>");
        assertThat(pom).doesNotContain("io.minio");
    }

    @Test
    void contentCenterSdkStaysInsideItsAdapterPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path javaRoot = REPO_ROOT.resolve("src/main/java");
        try (Stream<Path> files = Files.walk(javaRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.toString()
                            .replace('\\', '/').contains("infrastructure/contentcenter/"))
                    .forEach(file -> {
                        String content = read(file);
                        if (SDK_IMPORT.matcher(content).find()) {
                            offenders.add(REPO_ROOT.relativize(file).toString());
                        }
                    });
        }
        assertThat(offenders)
                .as("SDK types must stay inside infrastructure/contentcenter; found in: %s",
                        offenders)
                .isEmpty();
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".xml") || name.endsWith(".md") || name.endsWith(".sh")
                || name.endsWith(".sql") || name.endsWith(".properties")
                || name.endsWith(".example") || name.endsWith(".json") || name.endsWith(".txt");
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
