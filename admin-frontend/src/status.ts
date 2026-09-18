import type{Version}from"./api";
export const statusLabel:Record<Version["displayStatus"],string>={PENDING_REBUILD:"待重建",REBUILDING:"重建中",REBUILT:"已重建",CATCHING_UP:"补齐中",PUBLISHED:"已发布",NEEDS_ATTENTION:"需要处理"};
export const statusType=(status:Version["displayStatus"]):"success"|"warning"|"danger"|"info"=>status==="PUBLISHED"?"success":status==="NEEDS_ATTENTION"?"danger":status==="REBUILDING"||status==="CATCHING_UP"?"warning":"info";
