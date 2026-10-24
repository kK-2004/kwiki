import{describe,expect,it}from"vitest";import{statusLabel,statusType}from"../src/status";import{graphRunStageNote}from"../src/labels";
describe("index display labels",()=>{it("keeps the exact lifecycle wording",()=>{expect(Object.values(statusLabel)).toEqual(["待迁移","迁移中","已迁移","已发布","需要处理","不受支持"]);expect(Object.values(statusLabel)).not.toContain("已重建");expect(statusType("PUBLISHED")).toBe("success");expect(statusType("NEEDS_ATTENTION")).toBe("danger");expect(statusType("MIGRATING")).toBe("warning");expect(statusType("PENDING_MIGRATION")).toBe("info");expect(statusType("MIGRATED")).toBe("info");expect(statusType("UNSUPPORTED")).toBe("danger")})});

describe("graphRunStageNote", () => {
  it("shows the current stage while a run is active", () => {
    expect(graphRunStageNote("EXTRACTING", "EXTRACTING")).toBe("");
    expect(graphRunStageNote("WAITING_FOR_CHUNKS", "QUEUED")).toBe("排队中");
  });

  it("shows where a failed, cancelled or stale run stopped", () => {
    expect(graphRunStageNote("CANCELLED", "EXTRACTING")).toBe("中止于抽取阶段");
    expect(graphRunStageNote("FAILED", "SUMMARIZING")).toBe("中止于摘要阶段");
    expect(graphRunStageNote("CANCELLED", "QUEUED")).toBe("未开始");
  });

  it("hides the legacy CLEANING stage and stages of finished runs", () => {
    expect(graphRunStageNote("CANCELLED", "CLEANING")).toBe("");
    expect(graphRunStageNote("READY", "VALIDATING")).toBe("");
  });
});
