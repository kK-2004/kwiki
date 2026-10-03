import type{Version}from"./api";
export const statusLabel:Record<Version["displayStatus"],string>={PENDING_MIGRATION:"待迁移",MIGRATING:"迁移中",MIGRATED:"已迁移",PUBLISHED:"已发布",NEEDS_ATTENTION:"需要处理",UNSUPPORTED:"不受支持"};
export const statusType=(status:Version["displayStatus"]):"success"|"warning"|"danger"|"info"=>status==="PUBLISHED"?"success":status==="NEEDS_ATTENTION"||status==="UNSUPPORTED"?"danger":status==="MIGRATING"?"warning":"info";
