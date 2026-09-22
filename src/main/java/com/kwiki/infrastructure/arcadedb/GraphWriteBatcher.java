package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 将图批写限制在 500 条和 5 MiB 以内，避免远端请求无界增长。 */
final class GraphWriteBatcher {

    static final int MAX_ITEMS = 500;
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private GraphWriteBatcher() {
    }

    static <T> List<List<T>> split(List<T> values, ObjectMapper mapper) {
        List<List<T>> result = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int bytes = 0;
        for (T value : values) {
            int itemBytes;
            try {
                itemBytes = mapper.writeValueAsBytes(value).length;
            } catch (Exception e) {
                throw new IllegalArgumentException("图批写对象无法序列化", e);
            }
            if (itemBytes > MAX_BYTES) {
                throw new IllegalArgumentException("单条图批写记录超过 5 MiB");
            }
            if (!current.isEmpty() && (current.size() >= MAX_ITEMS || bytes + itemBytes > MAX_BYTES)) {
                result.add(List.copyOf(current));
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(value);
            bytes += itemBytes;
        }
        if (!current.isEmpty()) result.add(List.copyOf(current));
        return List.copyOf(result);
    }
}
