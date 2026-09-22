package com.kwiki.graph.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * 快照 READY 前的持久化校验报告，写入 graph_snapshot.validation_json。
 * 任何硬失败都阻止进入 READY；失败明细保留计数与缺口信息供诊断，不携带正文或实体名。
 */
public record GraphSnapshotValidationReport(
        long kbId,
        long graphVersion,
        long checkedSources,
        long readySources,
        long inputEntityCount,
        long memberEntityCount,
        long nonEmptyCommunityCount,
        long summarizedCommunityCount,
        long embeddedCommunityCount,
        List<Check> checks) {

    public static final String SOURCE_COVERAGE = "SOURCE_COVERAGE";
    public static final String EVENT_WATERMARK_CONTIGUOUS = "EVENT_WATERMARK_CONTIGUOUS";
    public static final String MEMBERSHIP_COMPLETE = "MEMBERSHIP_COMPLETE";
    public static final String ENTITY_FIELDS_READY = "ENTITY_FIELDS_READY";
    public static final String ENTITY_MAPPING_CONSISTENT = "ENTITY_MAPPING_CONSISTENT";
    public static final String SUMMARIES_COMPLETE = "SUMMARIES_COMPLETE";
    public static final String EMBEDDINGS_COMPLETE = "EMBEDDINGS_COMPLETE";
    public static final String MAPPING_CONFIG_IDENTITY = "MAPPING_CONFIG_IDENTITY";
    public static final String REFRESH_READABLE = "REFRESH_READABLE";

    /** 全部硬门禁检查必须出现并通过；缺失任一项的报告不能封存 READY。 */
    public static final List<String> REQUIRED_CHECKS = List.of(
            SOURCE_COVERAGE, EVENT_WATERMARK_CONTIGUOUS, MEMBERSHIP_COMPLETE,
            ENTITY_FIELDS_READY, ENTITY_MAPPING_CONSISTENT, SUMMARIES_COMPLETE,
            EMBEDDINGS_COMPLETE, MAPPING_CONFIG_IDENTITY, REFRESH_READABLE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GraphSnapshotValidationReport {
        if (kbId <= 0 || graphVersion <= 0 || checkedSources < 0 || readySources < 0
                || inputEntityCount < 0 || memberEntityCount < 0 || nonEmptyCommunityCount < 0
                || summarizedCommunityCount < 0 || embeddedCommunityCount < 0) {
            throw new IllegalArgumentException("校验报告计数无效");
        }
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public record Check(String name, boolean passed, String detail) {
        public static Check pass(String name, String detail) {
            return new Check(name, true, detail);
        }

        public static Check fail(String name, String detail) {
            return new Check(name, false, detail);
        }
    }

    public boolean valid() {
        return REQUIRED_CHECKS.stream().allMatch(this::passed);
    }

    public List<Check> failures() {
        return checks.stream().filter(check -> !check.passed()).toList();
    }

    /** 转成 GraphValidationChecklist 供发布事务复用同一硬门禁。 */
    public GraphValidationChecklist toChecklist() {
        return new GraphValidationChecklist(
                passed(SOURCE_COVERAGE) && passed(EVENT_WATERMARK_CONTIGUOUS),
                passed(MEMBERSHIP_COMPLETE),
                passed(ENTITY_MAPPING_CONSISTENT) && passed(ENTITY_FIELDS_READY),
                passed(SUMMARIES_COMPLETE),
                passed(EMBEDDINGS_COMPLETE),
                passed(MAPPING_CONFIG_IDENTITY),
                passed(REFRESH_READABLE));
    }

    private boolean passed(String name) {
        return checks.stream().anyMatch(check -> check.name().equals(name) && check.passed());
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException("校验报告序列化失败", failure);
        }
    }

    public static GraphSnapshotValidationReport fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, GraphSnapshotValidationReport.class);
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException("校验报告反序列化失败", failure);
        }
    }
}
