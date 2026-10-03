package com.kwiki.graph.build;

import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.UnweightedGraphProjection;
import com.kwiki.graph.persistence.EntityMappingObservation;
import com.kwiki.graph.persistence.GraphSourceManifest;
import com.kwiki.rag.retrieval.ChunkHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 阶段间传递的中间产物；由 GraphBuildRunner 在每次运行开始时创建。 */
public final class RunArtifacts {

    public List<GraphSourceChunk> sources = new ArrayList<>();
    public Map<String, ChunkHit> childrenBySourceChunkId = new LinkedHashMap<>();
    public List<GraphEntity> entities = new ArrayList<>();
    public List<GraphRelation> relations = new ArrayList<>();
    /** sourceChunkId → 该块消解后的正式 entityId。 */
    public Map<String, List<String>> extractedEntityIds = new LinkedHashMap<>();
    public UnweightedGraphProjection projection;
    public CommunityDetectionResult detection;
    public Map<String, CommunitySummary> summaries = new LinkedHashMap<>();
    public GraphSourceManifest manifest;
    /** 回填后各子块的实体映射观察值，供校验阶段使用。 */
    public Map<String, EntityMappingObservation> observations = new LinkedHashMap<>();
    public boolean communityIndexVerified;
    public long manifestId;
    public String manifestHash;
    public long snapshotId;
}
