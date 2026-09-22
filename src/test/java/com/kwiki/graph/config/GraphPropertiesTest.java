package com.kwiki.graph.config;

import com.kwiki.graph.GraphAlgorithmMode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphPropertiesTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static GraphProperties properties() {
        return new GraphProperties(false, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
    }

    @Test
    void disabledGraphDoesNotRequireArcadeDbEndpointOrCredentials() {
        GraphProperties graph = properties();
        ArcadeDbProperties arcadeDb = empty();

        assertThat(validator.validate(graph)).isEmpty();
        arcadeDb.requireUsableWhenEnabled(graph);
    }

    @Test
    void enabledGraphRequiresExplicitEndpointAndToken() {
        GraphProperties graph = new GraphProperties(true,
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());

        assertThatThrownBy(() -> empty().requireUsableWhenEnabled(graph))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint")
                .hasMessageNotContaining("token");
    }

    @Test
    void enabledGraphAcceptsSingleTokenWithBuildPermissions() {
        GraphProperties graph = new GraphProperties(true,
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), GraphProperties.Capacity.defaults());
        ArcadeDbProperties properties = new ArcadeDbProperties(
                URI.create("https://arcadedb.internal"), "kwiki", "arcade-contract-token",
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 8,
                new ArcadeDbProperties.Tls(true, true), "kwiki_leiden_", "24.6");

        properties.requireUsableWhenEnabled(graph);
    }

    private static ArcadeDbProperties empty() {
        return new ArcadeDbProperties(null, null, null,
                Duration.ofSeconds(3), Duration.ofMillis(1500), Duration.ofSeconds(30),
                Duration.ofMinutes(15), 8,
                new ArcadeDbProperties.Tls(false, true), "kwiki_leiden_", null);
    }
}
