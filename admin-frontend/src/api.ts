export interface Envelope<T>{code:number;message?:string;data:T}
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
  if(!response.ok||!body||body.code>=400)throw new ApiError(response.status,body?.message||"request_failed");return body.data;
}
const key=()=>crypto.randomUUID();
export const api={
  login:(username:string,password:string)=>request<{accessToken:string}>("/auth/login",{method:"POST",body:JSON.stringify({username,password})}),
  me:()=>request<{id:number;username:string;admin:boolean}>("/auth/me"),
  versions:()=>request<Version[]>("/admin/search-indexes/versions"),alias:()=>request<{alias:string;targets:string[]}>("/admin/search-indexes/alias"),
  runs:()=>request<Run[]>("/admin/search-indexes/runs"),validations:()=>request<Validation[]>("/admin/search-indexes/validations"),audits:()=>request<Audit[]>("/admin/search-indexes/audits"),stats:()=>request<Record<string,unknown>[]>("/admin/search-indexes/write-statistics"),
  command:<T>(path:string,method="POST",body?:unknown)=>request<T>(`/admin/search-indexes${path}`,{method,headers:{"Idempotency-Key":key()},body:body===undefined?undefined:JSON.stringify(body)}),
  deleteVersion:(version:number,name:string)=>request<{outcome:string}>(`/admin/search-indexes/versions/${version}`,{method:"DELETE",headers:{"Idempotency-Key":key()},body:JSON.stringify({confirmPhysicalName:name})})
};
