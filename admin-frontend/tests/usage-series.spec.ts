import { describe, expect, it } from "vitest";
import type { UsageSeries } from "../src/api";
import { allLeafKeys, buildLines, buildTree, leafKey, rangeFor, tableRows, totals } from "../src/usageSeries";

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
