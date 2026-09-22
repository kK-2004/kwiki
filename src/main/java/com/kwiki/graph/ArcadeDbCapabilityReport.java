package com.kwiki.graph;

/** ArcadeDB 服务能力探测结果；构建资格由全部能力项共同决定。 */
public record ArcadeDbCapabilityReport(
        boolean reachable,
        String serverVersion,
        boolean versionCompatible,
        boolean leidenSupported,
        boolean schemaSupported,
        boolean databaseCreationAllowed,
        boolean buildEnabled,
        String failureCode) {

    public ArcadeDbCapabilityReport {
        serverVersion = serverVersion == null ? "" : serverVersion;
        failureCode = failureCode == null ? "" : failureCode;
    }
}
