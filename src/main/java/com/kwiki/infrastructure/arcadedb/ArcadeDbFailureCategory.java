package com.kwiki.infrastructure.arcadedb;

/** 脱敏后供任务和监控使用的 ArcadeDB 故障分类。 */
public enum ArcadeDbFailureCategory {
    AUTHENTICATION,
    TIMEOUT,
    CONNECTIVITY,
    REMOTE,
    CANCELLED,
    CONFIGURATION,
    UNKNOWN
}
