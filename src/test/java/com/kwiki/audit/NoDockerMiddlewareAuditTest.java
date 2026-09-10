package com.kwiki.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI 防护：默认构建绝不得依赖 Docker Compose、Testcontainers、
 * 复制过来的 k-Rag 密钥，或中间件容器启动。kwiki 始终通过
 * KWIKI_* 配置连接运维方提供的 MySQL/Redis/content center/Elasticsearch。
 */
class NoDockerMiddlewareAuditTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();

    @Test
    void repositoryContainsNoComposeOrDockerfileMiddlewareDefinitions() {
        List<String> offenders = new ArrayList<>();
        for (String candidate : List.of(
                "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml",
                "Dockerfile", "Dockerfile.dev", "deploy/docker-compose.yml")) {
            if (Files.exists(REPO_ROOT.resolve(candidate))) {
                offenders.add(candidate);
            }
        }
        assertThat(offenders).as("middleware container definitions must not exist").isEmpty();
    }

    @Test
    void buildFilesDoNotDependOnTestcontainersOrDockerPlugins() throws IOException {
        for (String buildFile : List.of("pom.xml", "frontend/package.json")) {
            Path path = REPO_ROOT.resolve(buildFile);
            if (!Files.exists(path)) {
                continue;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(content)
                    .as(buildFile + " must not reference docker/testcontainers build deps")
                    .doesNotContainIgnoringCase("testcontainers")
                    .doesNotContainIgnoringCase("docker-maven-plugin");
        }
    }

    @Test
    void noDockerInvocationInDefaultBuildScripts() throws IOException {
        Path workflows = REPO_ROOT.resolve(".github/workflows");
        List<Path> buildScripts = new ArrayList<>();
        if (Files.isDirectory(workflows)) {
            try (var files = Files.list(workflows)) {
                files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                        .forEach(buildScripts::add);
            }
        }
        Path jenkinsfile = REPO_ROOT.resolve("Jenkinsfile");
        if (Files.exists(jenkinsfile)) {
            buildScripts.add(jenkinsfile);
        }
        for (Path script : buildScripts) {
            String content = Files.readString(script, StandardCharsets.UTF_8);
            assertThat(content)
                    .as(script.getFileName() + " must not run docker in the default build")
                    .doesNotContain("docker compose")
                    .doesNotContain("docker-compose")
                    .doesNotContain("testcontainers");
        }
    }

    @Test
    void auditScriptIsExecutableAndPasses() throws IOException, InterruptedException {
        Path script = REPO_ROOT.resolve("scripts/check-no-docker-middleware.sh");
        assertThat(script).as("scripts/check-no-docker-middleware.sh must exist").exists();
        assertThat(Files.isExecutable(script)).as("audit script must be executable").isTrue();

        Process process = new ProcessBuilder(script.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(finished).as("audit script must terminate").isTrue();
        assertThat(process.exitValue())
                .as("audit script must pass, output: " + output)
                .isZero();
    }
}
