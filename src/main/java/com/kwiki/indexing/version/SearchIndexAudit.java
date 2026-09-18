package com.kwiki.indexing.version;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name="search_index_audit")
public class SearchIndexAudit {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String action; private Integer targetVersion; private Long runId;
    private String operator; private Long configRevision; private String priorState;
    private String resultState; private String outcome; private String errorSummary;
    private Instant createdAt; private Instant updatedAt;
    protected SearchIndexAudit(){}
    static SearchIndexAudit pending(String action,Integer targetVersion,Long runId,String operator,
                                    Long revision,String prior){
        SearchIndexAudit audit=new SearchIndexAudit(); audit.action=action;
        audit.targetVersion=targetVersion; audit.runId=runId; audit.operator=operator;
        audit.configRevision=revision; audit.priorState=prior; audit.outcome="PENDING";
        audit.createdAt=Instant.now(); audit.updatedAt=audit.createdAt; return audit;
    }
    void succeed(String result){outcome="SUCCESS";resultState=result;updatedAt=Instant.now();}
    void fail(String error){outcome="FAILURE";errorSummary=error;updatedAt=Instant.now();}
    void recovered(String result){outcome="RECOVERED";resultState=result;updatedAt=Instant.now();}
    public Long getId(){return id;} public String getOutcome(){return outcome;}
    public Integer getTargetVersion(){return targetVersion;} public String getAction(){return action;}
    public Long getRunId(){return runId;} public String getOperator(){return operator;}
    public Long getConfigRevision(){return configRevision;} public String getPriorState(){return priorState;}
    public String getResultState(){return resultState;} public String getErrorSummary(){return errorSummary;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
