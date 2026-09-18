package com.kwiki.indexing.version;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "search_index_idempotency")
public class SearchIndexIdempotency {
    @Id
    private String idempotencyKey;
    private String action;
    private Integer targetVersion;
    private String operator;
    private String responseSummary;
    private Instant createdAt;

    protected SearchIndexIdempotency() { }

    static SearchIndexIdempotency pending(String key, String action, Integer targetVersion,
                                          String operator) {
        SearchIndexIdempotency row = new SearchIndexIdempotency();
        row.idempotencyKey = key;
        row.action = action;
        row.targetVersion = targetVersion;
        row.operator = operator;
        row.responseSummary = "PENDING";
        row.createdAt = Instant.now();
        return row;
    }

    void complete(String summary) { responseSummary = summary; }
    public String getAction() { return action; }
    public Integer getTargetVersion() { return targetVersion; }
    public String getOperator() { return operator; }
    public String getResponseSummary() { return responseSummary; }
}
