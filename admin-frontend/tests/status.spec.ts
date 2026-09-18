import{describe,expect,it}from"vitest";import{statusLabel}from"../src/status";
describe("index display labels",()=>{it("keeps the exact lifecycle wording",()=>{expect(Object.values(statusLabel)).toEqual(["待重建","重建中","已重建","补齐中","已发布","需要处理"]);expect(Object.values(statusLabel)).not.toContain("待补齐")})});
