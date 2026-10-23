export interface Envelope<T>{code:number;success?:boolean;message?:string;data:T}
export interface Config{parserVersion:string;chunkerVersion:string;embeddingProvider:string;embeddingModel:string;embeddingDimensions:number;indexSchema:string}
export interface MultimodalReadiness{ready:boolean;missingConfiguration:string[]}
export interface Version{indexSchemaLabel:string;schemaCapabilities:string[];versionNumber:number;physicalName:string;configuration:Config;configRevision:number;builtConfigRevision:number|null;dirty:boolean;displayStatus:"PENDING_MIGRATION"|"MIGRATING"|"MIGRATED"|"PUBLISHED"|"NEEDS_ATTENTION"|"UNSUPPORTED";buildState:string;catchupStatus:string;writeEnabled:boolean;adminDisabled:boolean;selected:boolean;kbScoped:boolean;pipelineSupported:boolean;healthSummary?:string;attentionReason?:string;lastValidationAt?:string;validationSummary?:string;cleanupCandidate:boolean;allowedActions:Record<string,boolean>;writeEnabledEventId:number|null;migrateBlockedReason:string|null;unsupportedReason:string|null}
export interface Range{resourceType:string;minId:number;maxId:number;lastSeenId:number;tailLastSeenId:number;tailMaxId:number|null;scanned:number;succeeded:number;skipped:number;failed:number}
export interface Run{runId:number;versionNumber:number;buildGeneration:number;kind:string;state:string;switchState:string;configRevision:number;buildStartEventId:number;replayEventId:number;dualWriteStartEventId:number|null;catchupBarrierEventId:number|null;scanned:number;succeeded:number;skipped:number;failed:number;requestedBy:string;startedAt:string;completedAt?:string;errorClass?:string;errorSummary?:string;ranges:Range[]}
export interface Validation{id:number;versionNumber:number;runId:number;status:string;summary:string;createdAt:string;[key:string]:unknown}
export interface Audit{id:number;action:string;targetVersion?:number;runId?:number;operator:string;priorState?:string;resultState?:string;outcome:string;errorSummary?:string;createdAt:string}
export type ModelTag="chat"|"vision"|"embedding";
export interface ModelView{id:number;providerId:number;modelName:string;displayName:string|null;tags:ModelTag[];dimensions:number|null;maxOutputTokens:number|null;supportsTools:boolean;supportsThinking:boolean;enabled:boolean;usedBy:string[];extra:Record<string,unknown>}
export interface ProviderView{id:number;name:string;protocol:string;baseUrl:string;apiKeyHint:string;requestTimeoutMs:number;maxRetries:number;maxConcurrency:number|null;qps:number|null;enabled:boolean;models:ModelView[]}
export interface ProviderRequest{name:string;baseUrl:string;apiKey?:string;requestTimeoutMs:number|null;maxRetries:number|null;maxConcurrency:number|null;qps:number|null;enabled:boolean}
export interface ModelRequest{modelName:string;displayName:string|null;tags:ModelTag[];dimensions:number|null;maxOutputTokens:number|null;supportsTools:boolean;supportsThinking:boolean;enabled:boolean;extra:Record<string,unknown>|null}
export interface AssignmentView{purpose:string;label:string;description:string;requiredTag:ModelTag;requiresTools:boolean;defaultTemperature:number;modelId:number|null;temperature:number|null;maxTokens:number|null;thinking:boolean|null;effectiveModel:string|null;extra:Record<string,unknown>}
export interface AssignmentRequest{purpose:string;modelId:number|null;temperature:number|null;maxTokens:number|null;thinking:boolean|null;extra:Record<string,unknown>|null}
export interface ModelTestResult{ok:boolean;message:string;latencyMs:number}
export interface ModelConfigStatus{version:number;loadedAt:string|null;lastError:string|null;databaseVersion:number}
const tokenKey="kwiki_access_token";
export const token={get:()=>localStorage.getItem(tokenKey),set:(value:string)=>localStorage.setItem(tokenKey,value),clear:()=>localStorage.removeItem(tokenKey)};
export class ApiError extends Error{constructor(public status:number,public code:string){super(code)}}
async function request<T>(path:string,init:RequestInit={}):Promise<T>{
  const headers=new Headers(init.headers);headers.set("Content-Type","application/json");const jwt=token.get();if(jwt)headers.set("Authorization",`Bearer ${jwt}`);
  const response=await fetch(`/api/v1${path}`,{...init,headers});let body:Envelope<T>|undefined;try{body=await response.json()}catch{throw new ApiError(response.status,"invalid_response")}
  if(!response.ok||!body||body.code>=400||body.success===false)throw new ApiError(response.status,body?.message||"request_failed");return body.data;
}
const key=()=>crypto.randomUUID();
const graphCommand=<T>(path:string,body?:unknown)=>request<T>(`/admin/knowledge-graphs${path}`,{method:"POST",headers:{"Idempotency-Key":key()},body:body===undefined?undefined:JSON.stringify(body)});
export interface CommunityVersion{versionNumber:number;batchId:number|null;mappingSchemaVersion:number;configRevision:number;state:string;createdAt:string;physicalIndexes:{kbId:number;communityPhysicalIndex:string;graphVersion:number;state:string}[]}
export interface GraphBatch{id:number;scopeKind:string;chunkIndexVersion:number;communityIndexVersion:number;state:string;autoPublish:boolean;requestedBy:string;scheduleDate:string|null;failureSummary:string|null;createdAt:string;extractor:string;communityDetector:string;summarizer:string}
export interface GraphRun{id:number;kbId:number;chunkIndexVersion:number;communityIndexVersion:number;communityPhysicalIndex:string;graphVersion:number;state:string;stage:string;entityCount:number;relationCount:number;sourceCount:number;communityCount:number;errorCode?:string;errorSummary?:string;startedAt?:string;completedAt?:string;extractor:string;communityDetector:string;summarizer:string;sourcesTotal:number;sourcesExtracted:number;extractionCacheHits:number;communitiesTotal:number;communitiesSummarized:number}
export interface GraphPublication{kbId:number;chunkIndexVersion:number;activeSnapshotId:number|null;graphVersion?:number;communityIndexVersion?:number;communityPhysicalIndex?:string;snapshotState?:string;publishedAt?:string}
export interface GraphSchedule{scheduleDate:string;status:string;linkedBatchId:number|null;createdAt:string}
export interface GraphSnapshotRow{id:number;kbId:number;graphVersion:number;chunkIndexVersion:number;communityIndexVersion:number;communityPhysicalIndex:string;state:string;entityCount:number;relationCount:number;communityCount:number;sealedAt?:string;retiredAt?:string;createdAt:string;extractor:string;communityDetector:string;summarizer:string}
export interface GraphPluginItem{id:string;label:string;available:boolean;unavailableReason:string|null}
export interface GraphPlugins{extractors:GraphPluginItem[];communityDetectors:GraphPluginItem[];summarizers:GraphPluginItem[];defaults:{extractor:string;communityDetector:string;summarizer:string}}
export interface GraphServiceStatus{enabled:boolean;algorithmMode:string;scheduleCron:string;scheduleZone:string;autoPublish:boolean;capacity:Record<string,number>;activeBatchId:number}
export interface PluginOption{id:string;label:string;available:boolean;unavailableReason:string|null}
export interface ParserOption extends PluginOption{requiredCapabilities:string[]}
export interface SchemaOption extends PluginOption{capabilities:string[]}
export interface PluginCatalog{schemas:SchemaOption[];parsers:ParserOption[];chunkers:PluginOption[];defaults:{indexSchema:string;parsers?:string[];chunker?:string}}
export interface GrayProgress{runId:number|null;runState:string|null;switchState:string|null;scanned:number;succeeded:number;failed:number;migrationPending:number;pendingTargets:number}
export interface GrayRelease{id:number;name:string;parserVersion:string;parserLabel:string;indexVersionNumber:number;physicalName:string;status:"CREATED"|"SYNCING"|"SYNCED"|"SWITCHED"|"ENDED";lastError:string|null;createdBy:string;createdAt:string;switchedAt:string|null;endedAt:string|null;kbs:{kbId:number;name:string}[];progress:GrayProgress;allowedActions:{sync:boolean;switch:boolean;switchBack:boolean;end:boolean}}
export interface ForceResetResult{deletedIndices:string[];purgedGraphTypes:string[];clearedRows:Record<string,number>;versionNumber:number;physicalName:string;migrationRunId:number|null;migrationError:string|null}
export interface EvalLayerMetric{coverage:number;fullHit:number;cases:number}
export interface EvalSummary{total:number;errors:number;scored:number;overall:Record<string,EvalLayerMetric>;byCategory:Record<string,Record<string,EvalLayerMetric>>;byDomain:Record<string,Record<string,EvalLayerMetric>>;bySource:Record<string,Record<string,EvalLayerMetric>>;negatives:number;refusalAccuracy:number;lossMatrix:Record<string,Record<string,number>>;contextGains:number}
export type EvalDataset="50q"|"100q"|"200q";
export type EvalRunState="QUEUED"|"RUNNING"|"COMPLETED"|"REPORT_PARTIAL"|"CANCELLED"|"FAILED";
export interface EvalRunStatus{runId:string;runName:string;dataset:string;state:EvalRunState;total:number;completed:number;summary:EvalSummary|null;error:string|null;localOnly:boolean;reportPartial:boolean;createdAt:string;ownerInstanceId:string|null;queuePosition:number|null}
export interface EvalDeleteResult{langfuseDeleted:boolean;langfuseError:string|null}
export interface EvalStartRequest{dataset:EvalDataset;kbId:number;runName?:string;notes?:string;localOnly:boolean}
export interface EvalImportResult{createdPageIds:number[];skippedTitles:string[]}
export type UsageSourceName="ONLINE"|"EVAL"|"BACKGROUND";
export interface UsageSeries{step:string;stepLabel:string;provider:string;model:string;calls:number[];failures:number[];inputTokens:number[];outputTokens:number[];latencyMs:number[]}
export interface UsageSeriesResponse{bucketSeconds:number;buckets:number[];series:UsageSeries[]}
export interface UsageUser{userId:number;displayName:string;totalTokens:number}
export interface UsageQuery{from:string;to:string;sources?:UsageSourceName[];users?:number[];bucket?:string;tzOffsetMinutes?:number}
const usageParams=(q:UsageQuery)=>{const p=new URLSearchParams({from:q.from,to:q.to});if(q.sources?.length)p.set("sources",q.sources.join(","));if(q.users?.length)p.set("users",q.users.join(","));if(q.bucket)p.set("bucket",q.bucket);if(q.tzOffsetMinutes!==undefined)p.set("tzOffsetMinutes",String(q.tzOffsetMinutes));return p.toString()};
export const FORCE_RESET_CONFIRMATION="RESET kwiki-chunks";
export const api={
  login:(username:string,password:string)=>request<{token:string;tokenType:string;expiresInSeconds:number;user:{id:number;username:string;admin:boolean}}>("/auth/login",{method:"POST",body:JSON.stringify({username,password})}),
  me:()=>request<{id:number;username:string;admin:boolean}>("/auth/me"),
  adminKnowledgeBases:()=>request<{id:number;name:string}[]>("/admin/knowledge-bases"),
  versions:()=>request<Version[]>("/admin/search-indexes/versions"),alias:()=>request<{alias:string;targets:string[]}>("/admin/search-indexes/alias"),
  multimodalReadiness:()=>request<MultimodalReadiness>("/admin/search-indexes/multimodal-readiness"),
  runs:()=>request<Run[]>("/admin/search-indexes/runs"),validations:()=>request<Validation[]>("/admin/search-indexes/validations"),audits:()=>request<Audit[]>("/admin/search-indexes/audits"),stats:()=>request<Record<string,unknown>[]>("/admin/search-indexes/write-statistics"),
  command:<T>(path:string,method="POST",body?:unknown)=>request<T>(`/admin/search-indexes${path}`,{method,headers:{"Idempotency-Key":key()},body:body===undefined?undefined:JSON.stringify(body)}),
  deleteVersion:(version:number,name:string)=>request<{outcome:string}>(`/admin/search-indexes/versions/${version}`,{method:"DELETE",headers:{"Idempotency-Key":key()},body:JSON.stringify({confirmPhysicalName:name})}),
  plugins:()=>request<PluginCatalog>("/admin/search-indexes/plugins"),
  forceReset:(confirmation:string)=>request<ForceResetResult>("/admin/search-indexes/force-reset",{method:"POST",body:JSON.stringify({confirmation})}),
  parsers:()=>request<ParserOption[]>("/admin/search-indexes/parsers"),
  grayReleases:()=>request<GrayRelease[]>("/admin/search-indexes/gray-releases"),
  createGrayRelease:(body:{name?:string;parserVersion:string;indexSchema?:string;chunkerVersion?:string;kbIds:number[]})=>request<{id:number;status:string}>("/admin/search-indexes/gray-releases",{method:"POST",headers:{"Idempotency-Key":key()},body:JSON.stringify(body)}),
  grayCommand:(id:number,action:"sync"|"switch"|"switch-back"|"end")=>request<{id:number;status:string}>(`/admin/search-indexes/gray-releases/${id}/${action}`,{method:"POST",headers:{"Idempotency-Key":key()}}),
  graphBatches:()=>request<GraphBatch[]>("/admin/knowledge-graphs/batches"),
  graphRuns:(batchId:number)=>request<GraphRun[]>(`/admin/knowledge-graphs/batches/${batchId}/runs`),
  graphSchedule:()=>request<GraphSchedule[]>("/admin/knowledge-graphs/schedule"),
  graphPublications:()=>request<GraphPublication[]>("/admin/knowledge-graphs/publications"),
  graphSnapshots:()=>request<GraphSnapshotRow[]>("/admin/knowledge-graphs/snapshots"),
  communityVersions:()=>request<CommunityVersion[]>("/admin/knowledge-graphs/community-versions"),
  graphAudits:()=>request<Record<string,unknown>[]>("/admin/knowledge-graphs/audits"),
  graphServiceStatus:()=>request<GraphServiceStatus>("/admin/knowledge-graphs/service-status"),
  graphPlugins:()=>request<GraphPlugins>("/admin/knowledge-graphs/plugins"),
  modelProviders:()=>request<ProviderView[]>("/admin/models/providers"),
  createModelProvider:(body:ProviderRequest)=>request<ProviderView>("/admin/models/providers",{method:"POST",body:JSON.stringify(body)}),
  updateModelProvider:(id:number,body:ProviderRequest)=>request<ProviderView>(`/admin/models/providers/${id}`,{method:"PUT",body:JSON.stringify(body)}),
  deleteModelProvider:(id:number)=>request<boolean>(`/admin/models/providers/${id}`,{method:"DELETE"}),
  testModelProvider:(body:{providerId:number|null;baseUrl:string;apiKey?:string;requestTimeoutMs:number|null})=>request<ModelTestResult>("/admin/models/providers/test",{method:"POST",body:JSON.stringify(body)}),
  discoverModels:(id:number)=>request<string[]>(`/admin/models/providers/${id}/discover`,{method:"POST"}),
  createModels:(providerId:number,body:ModelRequest[])=>request<ModelView[]>(`/admin/models/providers/${providerId}/models`,{method:"POST",body:JSON.stringify(body)}),
  updateModel:(id:number,body:ModelRequest)=>request<ModelView>(`/admin/models/models/${id}`,{method:"PUT",body:JSON.stringify(body)}),
  deleteModel:(id:number)=>request<boolean>(`/admin/models/models/${id}`,{method:"DELETE"}),
  testModel:(id:number)=>request<ModelTestResult>(`/admin/models/models/${id}/test`,{method:"POST"}),
  modelAssignments:()=>request<AssignmentView[]>("/admin/models/assignments"),
  saveModelAssignments:(body:AssignmentRequest[])=>request<{version:number;assignments:AssignmentView[]}>("/admin/models/assignments",{method:"PUT",body:JSON.stringify(body)}),
  modelConfigStatus:()=>request<ModelConfigStatus>("/admin/models/status"),
  graphCommand,
  evalImportCorpus:(kbId:number,corpus="dragonball-zh")=>request<EvalImportResult>(`/admin/eval/corpus/import?kbId=${kbId}&corpus=${encodeURIComponent(corpus)}`,{method:"POST"}),
  evalSyncDatasets:()=>request<{items:number;written:number;skipped:number}>("/admin/eval/datasets/sync",{method:"POST"}),
  evalStartRun:(body:EvalStartRequest)=>request<EvalRunStatus>("/admin/eval/runs",{method:"POST",body:JSON.stringify(body)}),
  evalRunStatus:(runId:string)=>request<EvalRunStatus>(`/admin/eval/runs/${encodeURIComponent(runId)}`),
  evalListRuns:()=>request<EvalRunStatus[]>("/admin/eval/runs"),
  evalCancelRun:(runId:string)=>request<EvalRunStatus>(`/admin/eval/runs/${encodeURIComponent(runId)}/cancel`,{method:"POST"}),
  evalResumeRun:(runId:string)=>request<EvalRunStatus>(`/admin/eval/runs/${encodeURIComponent(runId)}/resume`,{method:"POST"}),
  evalRetryFailedRun:(runId:string)=>request<EvalRunStatus>(`/admin/eval/runs/${encodeURIComponent(runId)}/retry-failed`,{method:"POST"}),
  evalDeleteRun:(runId:string,langfuse:boolean)=>request<EvalDeleteResult>(`/admin/eval/runs/${encodeURIComponent(runId)}?langfuse=${langfuse}`,{method:"DELETE"}),
  evalRunReport:(runId:string)=>request<Record<string,unknown>>(`/admin/eval/runs/${encodeURIComponent(runId)}/report`),
  modelUsageSeries:(q:UsageQuery)=>request<UsageSeriesResponse>(`/admin/model-usage/series?${usageParams(q)}`),
  modelUsageUsers:(q:UsageQuery)=>request<UsageUser[]>(`/admin/model-usage/users?${usageParams({from:q.from,to:q.to,sources:q.sources})}`),
  graphSubmit:(payload:Record<string,unknown>)=>graphCommand<{batchId:number;chunkIndexSchema:string;communityIndexVersion:number;runIds:number[];replayed:boolean}>("/batches",payload)
};
