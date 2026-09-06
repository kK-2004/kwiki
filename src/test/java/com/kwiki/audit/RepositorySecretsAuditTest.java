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
 * Repository audit that rejects committed credential-like defaults. Secrets must only
 * ever arrive through KWIKI_* environment variables; the tracked .env.example and
 * application*.yml files must stay credential-free, and no real .env file may be
 * committed. Also guards against credentials copied over from k-Rag.
 */
class RepositorySecretsAuditTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();

    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(sk-[A-Za-z0-9_-]{12,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|"
                    + "AIza[0-9A-Za-z_-]{30,}|xoxb-[0-9A-Za-z-]{10,}|Bearer\\s+[A-Za-z0-9._-]{16,})");

    private static final Pattern SECRET_ENV_KEY = Pattern.compile(
            ".*(PASSWORD|API_KEY|SECRET|TOKEN).*");

    private static final Pattern SECRET_YAML_KEY = Pattern.compile(
            "^\\s*(password|api-key|access-key|secret-key|apikey|authorization)\\s*:.*$",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noRealEnvFileIsCommitted() throws IOException {
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO_ROOT, 3)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example"));
                    })
                    .forEach(offenders::add);
        }
        assertThat(offenders).as("committed .env files (only .env.example may exist)").isEmpty();
    }

    @Test
    void envExampleContainsNoCredentialValues() throws IOException {
        Path example = REPO_ROOT.resolve(".env.example");
        assertThat(example).as(".env.example must exist").exists();

        List<String> offenders = new ArrayList<>();
        for (String line : Files.readAllLines(example, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = trimmed.substring(0, eq);
            String value = trimmed.substring(eq + 1);
            if (SECRET_ENV_KEY.matcher(key).matches()) {
                if (!value.isBlank()) {
                    offenders.add("secret key " + key + " must stay empty, got: " + value);
                }
            }
            if (CREDENTIAL_LIKE.matcher(value).find()) {
                offenders.add("credential-like value for " + key + ": " + value);
            }
        }
        assertThat(offenders).as(".env.example must be credential-free").isEmpty();
    }

    @Test
    void applicationYamlKeepsSecretsAsPlaceholders() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path yml : List.of(
                REPO_ROOT.resolve("src/main/resources/application.yml"),
                REPO_ROOT.resolve("src/main/resources/application-local.yml"))) {
            assertThat(yml).exists();
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                if (SECRET_YAML_KEY.matcher(line).matches()) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (!value.isEmpty() && !value.startsWith("${")) {
                        offenders.add(yml.getFileName() + " literal secret value: " + line.trim());
                    }
                }
                if (CREDENTIAL_LIKE.matcher(line).find()) {
                    offenders.add(yml.getFileName() + " credential-like content: " + line.trim());
                }
            }
        }
        assertThat(offenders).as("yaml secrets must be ${...} placeholders").isEmpty();
    }

    @Test
    void mainResourcesAndSourcesContainNoCredentialLikeDefaults() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(
                REPO_ROOT.resolve("src/main/resources"),
                REPO_ROOT.resolve("src/main/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yml")
                                || p.toString().endsWith(".yaml") || p.toString().endsWith(".properties")
                                || p.toString().endsWith(".sql"))
                        .forEach(p -> {
                            try {
                                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                                    if (CREDENTIAL_LIKE.matcher(line).find()) {
                                        offenders.add(REPO_ROOT.relativize(p) + ": " + line.trim());
                                    }
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }
        assertThat(offenders).as("sources must not embed credential-like literals").isEmpty();
    }
}
