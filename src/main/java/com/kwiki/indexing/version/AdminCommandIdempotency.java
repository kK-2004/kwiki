package com.kwiki.indexing.version;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.function.Supplier;

/** 管理命令的数据库幂等门；响应只持久化净化后的标量摘要。 */
@Service
@ConditionalOnBean(SearchIndexIdempotencyRepository.class)
public class AdminCommandIdempotency {
    private final SearchIndexIdempotencyRepository rows;
    private final ObjectMapper json;
    private final SearchIndexAuditRepository audits;

    @Autowired
    public AdminCommandIdempotency(SearchIndexIdempotencyRepository rows, ObjectMapper json,
                                   SearchIndexAuditRepository audits) {
        this.rows = rows; this.json = json; this.audits=audits;
    }

    AdminCommandIdempotency(SearchIndexIdempotencyRepository rows,ObjectMapper json){
        this.rows=rows;this.json=json;this.audits=null;
    }

    public Map<String, Object> execute(String key, String action, Integer targetVersion,
                                       String operator, Supplier<Map<String, Object>> command) {
        String normalized = requireKey(key);
        SearchIndexIdempotency prior = rows.findById(normalized).orElse(null);
        if (prior != null) return replay(prior, action, targetVersion, operator);
        SearchIndexIdempotency row = SearchIndexIdempotency.pending(normalized, action,
                targetVersion, operator);
        try {
            rows.saveAndFlush(row);
        } catch (DataIntegrityViolationException duplicate) {
            return replay(rows.findById(normalized).orElseThrow(() -> duplicate),
                    action, targetVersion, operator);
        }
        SearchIndexAudit audit=audits==null?null:audits.saveAndFlush(SearchIndexAudit.pending(
                action,targetVersion,null,operator,null,"REQUEST_ACCEPTED"));
        Map<String, Object> response;
        try { response=java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(command.get())); }
        catch(RuntimeException failure){
            String summary=sanitize(failure);
            row.complete("FAILURE:"+summary);rows.save(row);
            if(audit!=null){audit.fail(summary);audits.save(audit);}
            throw failure;
        }
        try {
            row.complete("SUCCESS:" + json.writeValueAsString(response));
            rows.save(row);
            if(audit!=null){audit.succeed("SUCCESS");audits.save(audit);}
        } catch (Exception failure) {
            row.complete("FAILURE:response_serialization");
            rows.save(row);
            throw new IllegalStateException("management response could not be recorded");
        }
        return response;
    }

    private Map<String, Object> replay(SearchIndexIdempotency row, String action,
                                       Integer targetVersion, String operator) {
        if (!action.equals(row.getAction()) || !java.util.Objects.equals(row.getTargetVersion(), targetVersion)
                || !operator.equals(row.getOperator())) {
            throw new IllegalArgumentException("idempotency key belongs to another command");
        }
        String summary = row.getResponseSummary();
        if (summary == null || summary.equals("PENDING")) {
            throw new ConflictException("management_command_in_progress");
        }
        if (!summary.startsWith("SUCCESS:")) {
            throw new ConflictException("previous_management_command_failed");
        }
        try {
            return json.readValue(summary.substring(8), new TypeReference<>() { });
        } catch (Exception failure) {
            throw new IllegalStateException("stored management response is invalid");
        }
    }

    private static String requireKey(String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty() || value.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 200 characters");
        }
        return value;
    }

    private static String sanitize(RuntimeException failure){String message=failure.getMessage();
        String value=message==null?failure.getClass().getSimpleName():message;
        return value.substring(0,Math.min(500,value.length()));}
}
