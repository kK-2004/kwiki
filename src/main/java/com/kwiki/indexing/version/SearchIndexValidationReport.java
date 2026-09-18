package com.kwiki.indexing.version;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "search_index_validation_report")
public class SearchIndexValidationReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private int versionNumber;
    private Long runId;
    private long configRevision;
    private String status;
    private boolean revisionValid;
    private boolean manifestValid;
    private boolean mappingValid;
    private boolean vectorDimensionValid;
    private boolean requiredFieldsValid;
    private boolean coverageValid;
    private long sourceResources;
    private long missingResources;
    private boolean integrityValid;
    private long staleDocuments;
    private long orphanChildren;
    private long malformedVectors;
    private long mixedManifestDocuments;
    private boolean synchronizationValid;
    private boolean smokeQueriesValid;
    private Long barrierEventId;
    private String cursorFingerprint;
    private String aliasFingerprint;
    private String summary;
    private Instant createdAt;
    protected SearchIndexValidationReport() { }
    SearchIndexValidationReport(int versionNumber, Long runId, long configRevision,
            boolean revisionValid, boolean manifestValid, boolean mappingValid,
            boolean vectorDimensionValid, boolean requiredFieldsValid,
            boolean coverageValid, long sourceResources, long missingResources,
            boolean integrityValid, long staleDocuments, long orphanChildren,
            long malformedVectors, long mixedManifestDocuments,
            boolean synchronizationValid, boolean smokeQueriesValid,
            Long barrierEventId, String cursorFingerprint, String aliasFingerprint,
            String summary, Instant now) {
        this.versionNumber=versionNumber; this.runId=runId; this.configRevision=configRevision;
        this.revisionValid=revisionValid; this.manifestValid=manifestValid;
        this.mappingValid=mappingValid; this.vectorDimensionValid=vectorDimensionValid;
        this.requiredFieldsValid=requiredFieldsValid;
        this.coverageValid=coverageValid; this.sourceResources=sourceResources;
        this.missingResources=missingResources;
        this.integrityValid=integrityValid; this.staleDocuments=staleDocuments;
        this.orphanChildren=orphanChildren; this.malformedVectors=malformedVectors;
        this.mixedManifestDocuments=mixedManifestDocuments;
        this.synchronizationValid=synchronizationValid;
        this.smokeQueriesValid=smokeQueriesValid;
        this.barrierEventId=barrierEventId; this.cursorFingerprint=cursorFingerprint;
        this.aliasFingerprint=aliasFingerprint;
        this.status=revisionValid && manifestValid && mappingValid && coverageValid
                && integrityValid && synchronizationValid && smokeQueriesValid ? "PASS" : "FAIL";
        this.summary=summary; this.createdAt=now;
    }
    public Long getId(){return id;} public String getStatus(){return status;}
    public String getSummary(){return summary;} public int getVersionNumber(){return versionNumber;}
    public Long getRunId(){return runId;}
    public boolean isRevisionValid(){return revisionValid;}
    public boolean isManifestValid(){return manifestValid;}
    public boolean isMappingValid(){return mappingValid;}
    public boolean isVectorDimensionValid(){return vectorDimensionValid;}
    public boolean isRequiredFieldsValid(){return requiredFieldsValid;}
    public boolean isCoverageValid(){return coverageValid;}
    public long getSourceResources(){return sourceResources;}
    public long getMissingResources(){return missingResources;}
    public boolean isIntegrityValid(){return integrityValid;}
    public long getStaleDocuments(){return staleDocuments;}
    public long getOrphanChildren(){return orphanChildren;}
    public long getMalformedVectors(){return malformedVectors;}
    public long getMixedManifestDocuments(){return mixedManifestDocuments;}
    public boolean isSynchronizationValid(){return synchronizationValid;}
    public boolean isSmokeQueriesValid(){return smokeQueriesValid;}
    public long getConfigRevision(){return configRevision;}
    public Long getBarrierEventId(){return barrierEventId;}
    public String getCursorFingerprint(){return cursorFingerprint;}
    public String getAliasFingerprint(){return aliasFingerprint;}
    public Instant getCreatedAt(){return createdAt;}
}
