package com.kwiki.indexing.search;

import java.util.List;
import java.util.Map;

/**
 * Pure validation of an existing index mapping against the kwiki contract:
 * vector dimensions must match the configured value and every required
 * provenance/scope field must exist. Alias switching is rejected otherwise.
 */
public final class MappingValidator {

    private static final List<String> REQUIRED_FIELDS = List.of(
            "chunkLevel", "chunkKey", "parentChunkKey", "resourceType", "resourceId",
            "revisionId", "kbId", "headingPath", "charStart", "charEnd", "content",
            "parserVersion", "chunkerVersion", "embeddingModel", "indexVersion", "vector");

    private MappingValidator() {
    }

    /** Returns an error description, or null when the mapping is compatible. */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> indexMapping, int expectedDimensions) {
        Object mappingsNode = indexMapping.get("mappings");
        if (!(mappingsNode instanceof Map<?, ?> mappings)) {
            return "missing mappings section";
        }
        Object propertiesNode = ((Map<String, Object>) mappings).get("properties");
        if (!(propertiesNode instanceof Map<?, ?> properties)) {
            return "missing properties section";
        }
        Map<String, Object> typed = (Map<String, Object>) properties;
        for (String field : REQUIRED_FIELDS) {
            if (!typed.containsKey(field)) {
                return "missing required field: " + field;
            }
        }
        Object vector = typed.get("vector");
        if (vector instanceof Map<?, ?> vectorField) {
            Object dims = vectorField.get("dims");
            if (!(dims instanceof Number number) || number.intValue() != expectedDimensions) {
                return "vector dimension mismatch: expected " + expectedDimensions;
            }
            if (!Boolean.TRUE.equals(vectorField.get("index"))) {
                return "vector field is not indexed for kNN";
            }
            return null;
        }
        return "vector field is malformed";
    }
}
