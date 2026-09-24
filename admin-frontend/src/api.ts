export interface Envelope<T>{code:number;success?:boolean;message?:string;data:T}
export interface Config{parserVersion:string;chunkerVersion:string;embeddingProvider:string;embeddingModel:string;embeddingDimensions:number;mappingSchemaVersion:number}
export interface Version{versionNumber:number;physicalName:string;configuration:Config;configRevision:number;builtConfigRevision:number|null;dirty:boolean;displayStatus:"PENDING_REBUILD"|"REBUILDING"|"REBUILT"|"CATCHING_UP"|"PUBLISHED"|"NEEDS_ATTENTION";buildState:string;catchupStatus:string;writeEnabled:boolean;adminDisabled:boolean;selected:boolean;pipelineSupported:boolean;healthSummary?:string;attentionReason?:string;lastValidationAt?:string;validationSummary?:string;cleanupCandidate:boolean;allowedActions:Record<string,boolean>}
export interface Range{resourceType:string;minId:number;maxId:number;lastSeenId:number;tailLastSeenId:number;tailMaxId:number|null;scanned:number;succeeded:number;skipped:number;failed:number}
export interface Run{runId:number;versionNumber:number;buildGeneration:number;kind:string;state:string;switchState:string;configRevision:number;buildStartEventId:number;replayEventId:number;dualWriteStartEventId:number|null;catchupBarrierEventId:number|null;scanned:number;succeeded:number;skipped:number;failed:number;requestedBy:string;startedAt:string;completedAt?:string;errorClass?:string;errorSummary?:string;ranges:Range[]}
export interface Validation{id:number;versionNumber:number;runId:number;status:string;summary:string;createdAt:string;[key:string]:unknown}
export interface Audit{id:number;action:string;targetVersion?:number;runId?:number;operator:string;priorState?:string;resultState?:string;outcome:string;errorSummary?:string;createdAt:string}
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
export interface GraphBatch{id:number;scopeKind:string;chunkIndexVersion:number;communityIndexVersion:number;state:string;autoPublish:boolean;requestedBy:string;scheduleDate:string|null;failureSummary:string|null;createdAt:string}
export interface GraphRun{id:number;kbId:number;chunkIndexVersion:number;communityIndexVersion:number;communityPhysicalIndex:string;graphVersion:number;state:string;stage:string;entityCount:number;relationCount:number;sourceCount:number;communityCount:number;errorCode?:string;errorSummary?:string;startedAt?:string;completedAt?:string}
export interface GraphPublication{kbId:number;chunkIndexVersion:number;activeSnapshotId:number|null;graphVersion?:number;communityIndexVersion?:number;communityPhysicalIndex?:string;snapshotState?:string;publishedAt?:string}
export interface GraphSchedule{scheduleDate:string;status:string;linkedBatchId:number|null;createdAt:string}
export interface GraphSnapshotRow{id:number;kbId:number;graphVersion:number;chunkIndexVersion:number;communityIndexVersion:number;communityPhysicalIndex:string;state:string;entityCount:number;relationCount:number;communityCount:number;sealedAt?:string;retiredAt?:string;createdAt:string}
export interface GraphServiceStatus{enabled:boolean;algorithmMode:string;scheduleCron:string;scheduleZone:string;autoPublish:boolean;capacity:Record<string,number>;activeBatchId:number}
export const api={
  login:(username:string,password:string)=>request<{token:string;tokenType:string;expiresInSeconds:number;user:{id:number;username:string;admin:boolean}}>("/auth/login",{method:"POST",body:JSON.stringify({username,password})}),
  me:()=>request<{id:number;username:string;admin:boolean}>("/auth/me"),
  adminKnowledgeBases:()=>request<{id:number;name:string}[]>("/admin/knowledge-bases"),
  versions:()=>request<Version[]>("/admin/search-indexes/versions"),alias:()=>request<{alias:string;targets:string[]}>("/admin/search-indexes/alias"),
  runs:()=>request<Run[]>("/admin/search-indexes/runs"),validations:()=>request<Validation[]>("/admin/search-indexes/validations"),audits:()=>request<Audit[]>("/admin/search-indexes/audits"),stats:()=>request<Record<string,unknown>[]>("/admin/search-indexes/write-statistics"),
  command:<T>(path:string,method="POST",body?:unknown)=>request<T>(`/admin/search-indexes${path}`,{method,headers:{"Idempotency-Key":key()},body:body===undefined?undefined:JSON.stringify(body)}),
  deleteVersion:(version:number,name:string)=>request<{outcome:string}>(`/admin/search-indexes/versions/${version}`,{method:"DELETE",headers:{"Idempotency-Key":key()},body:JSON.stringify({confirmPhysicalName:name})}),
  graphBatches:()=>request<GraphBatch[]>("/admin/knowledge-graphs/batches"),
  graphRuns:(batchId:number)=>request<GraphRun[]>(`/admin/knowledge-graphs/batches/${batchId}/runs`),
  graphSchedule:()=>request<GraphSchedule[]>("/admin/knowledge-graphs/schedule"),
  graphPublications:()=>request<GraphPublication[]>("/admin/knowledge-graphs/publications"),
  graphSnapshots:()=>request<GraphSnapshotRow[]>("/admin/knowledge-graphs/snapshots"),
  communityVersions:()=>request<CommunityVersion[]>("/admin/knowledge-graphs/community-versions"),
  graphAudits:()=>request<Record<string,unknown>[]>("/admin/knowledge-graphs/audits"),
  graphServiceStatus:()=>request<GraphServiceStatus>("/admin/knowledge-graphs/service-status"),
  graphCommand,
  graphSubmit:(payload:Record<string,unknown>)=>graphCommand<{batchId:number;communityIndexVersion:number;runIds:number[];replayed:boolean}>("/batches",payload)
};
