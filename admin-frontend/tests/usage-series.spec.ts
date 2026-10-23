import { describe, expect, it } from "vitest";
import type { UsageSeries } from "../src/api";
import { allLeafKeys, bucketLabel, buildLines, buildTree, focusRange, foldLines, leafKey, rangeFor, sumRange, tableRows, totals } from "../src/usageSeries";

const s = (step: string, stepLabel: string, model: string, input: number[], output: number[] = input.map(() => 0)): UsageSeries => ({
  step, stepLabel, provider: "dashscope", model,
  calls: input.map(() => 1), failures: input.map(() => 0), inputTokens: input, outputTokens: output, latencyMs: input.map(() => 10),
});
const data = [
  s("ANSWER", "回答生成", "qwen-plus", [10, 20], [1, 2]),
  s("ANSWER", "回答生成", "qwen-max", [5, 5]),
  s("REWRITE", "查询改写", "qwen-plus", [1, 1]),
];

describe("维度树", () => {
  it("按步骤：步骤 → 模型，按总 token 降序", () => {
    const tree = buildTree(data, "step");
    expect(tree.map(n => n.label)).toEqual(["回答生成", "查询改写"]);
    expect(tree[0].children.map(c => c.label)).toEqual(["dashscope / qwen-plus", "dashscope / qwen-max"]);
  });
  it("按模型：provider/模型 → 步骤，按总 token 降序", () => {
    const tree = buildTree(data, "model");
    expect(tree.map(n => n.label)).toEqual(["dashscope / qwen-plus", "dashscope / qwen-max"]);
    expect(tree[0].children.map(c => c.label)).toEqual(["回答生成", "查询改写"]);
    expect(allLeafKeys(tree)).toHaveLength(3);
  });
});

describe("折线分组", () => {
  it("一级节点全选时合并为一条线", () => {
    const tree = buildTree(data, "step");
    const lines = buildLines(data, tree, new Set(allLeafKeys(tree)), "step", "total");
    expect(lines.map(l => l.name)).toEqual(["回答生成", "查询改写"]);
    expect(lines[0].values).toEqual([16, 27]);
  });
  it("部分勾选时按叶子拆线", () => {
    const tree = buildTree(data, "step");
    const lines = buildLines(data, tree, new Set([leafKey(data[0]), leafKey(data[2])]), "step", "input");
    expect(lines.map(l => l.name)).toEqual(["回答生成 / dashscope / qwen-plus", "查询改写"]);
  });
  it("按模型分组时同一模型跨步骤合并", () => {
    const tree = buildTree(data, "model");
    const lines = buildLines(data, tree, new Set(allLeafKeys(tree)), "model", "input");
    expect(lines.find(l => l.name === "dashscope / qwen-plus")?.values).toEqual([11, 21]);
  });
});

describe("汇总与明细", () => {
  it("只统计勾选的叶子", () => {
    const t = totals(data, new Set([leafKey(data[0])]));
    expect(t).toMatchObject({ input: 30, output: 3, total: 33, calls: 2, failures: 0, failureRate: 0, avgLatencyMs: 10 });
  });
  it("明细行按总量降序", () => {
    const tree = buildTree(data, "step");
    const rows = tableRows(buildLines(data, tree, new Set(allLeafKeys(tree)), "step", "total"));
    expect(rows[0]).toMatchObject({ name: "回答生成", total: 43 });
  });
});

describe("时间范围", () => {
  it("今天为本地零点到明天零点", () => {
    const now = new Date(2026, 9, 21, 15, 30);
    const { from, to } = rangeFor("today", now);
    expect(from.getHours()).toBe(0);
    expect(to.getTime() - from.getTime()).toBe(24 * 3600 * 1000);
    expect(from.getDate()).toBe(21);
  });
  it("近 7 天以今天结束", () => {
    const { from, to } = rangeFor("7d", new Date(2026, 9, 21, 15, 30));
    expect((to.getTime() - from.getTime()) / 86_400_000).toBe(7);
  });
});

describe("图表选区", () => {
  it("聚焦最后一段连续调用，前后各留 2 个桶", () => {
    const v = [0, 5, 0, 0, 0, 0, 0, 0, 0, 3, 0, 4, 0, 0];
    expect(focusRange(v, 3)).toEqual([7, 13]);
  });
  it("间隔不超过 gap 时并入同一波", () => {
    expect(focusRange([0, 0, 1, 0, 0, 1, 0], 3)).toEqual([0, 6]);
  });
  it("全为零或为空时返回全部范围", () => {
    expect(focusRange([0, 0, 0], 3)).toEqual([0, 2]);
    expect(focusRange([], 3)).toEqual([0, 0]);
  });
  it("区间求和含两端", () => {
    expect(sumRange([1, 2, 3, 4], [1, 2])).toBe(5);
  });
  it("超过上限的维度折叠为「其他」", () => {
    const line = (name: string, v: number) => ({ name, values: [v, v], leaves: [] });
    const folded = foldLines([line("a", 1), line("b", 2), line("c", 3), line("d", 4)], 3);
    expect(folded.map(l => l.name)).toEqual(["a", "b", "其他（2 项）"]);
    expect(folded[2].values).toEqual([7, 7]);
    expect(foldLines([line("a", 1)], 3)).toHaveLength(1);
  });
  it("跨天范围的时间标签带日期", () => {
    const t = new Date(2026, 9, 23, 9, 5).getTime();
    expect(bucketLabel(t, false)).toBe("09:05");
    expect(bucketLabel(t, true)).toBe("10-23 09:05");
    expect(bucketLabel(new Date(2026, 9, 24).getTime(), false, true)).toBe("24:00");
    expect(bucketLabel(new Date(2026, 9, 24).getTime(), true, true)).toBe("10-24 00:00");
  });
});
