import type { UsageSeries } from "./api";

export type Metric = "total" | "input" | "output" | "calls" | "failures";
export type GroupBy = "step" | "model";
export type RangePreset = "today" | "yesterday" | "7d" | "30d";

export interface TreeNode { id: string; label: string; children: TreeNode[] }
export interface Line { name: string; values: number[]; leaves: UsageSeries[] }
export interface Totals { total: number; input: number; output: number; calls: number; failures: number; failureRate: number; avgLatencyMs: number }
export interface TableRow extends Totals { name: string }

const modelLabel = (s: UsageSeries) => `${s.provider} / ${s.model}`;
export const leafKey = (s: UsageSeries) => `${s.step}|${s.provider}|${s.model}`;

export function metricValues(s: UsageSeries, metric: Metric): number[] {
  switch (metric) {
    case "input": return s.inputTokens;
    case "output": return s.outputTokens;
    case "calls": return s.calls;
    case "failures": return s.failures;
    default: return s.inputTokens.map((v, i) => v + s.outputTokens[i]);
  }
}

const tokenTotal = (s: UsageSeries) =>
  s.inputTokens.reduce((a, b) => a + b, 0) + s.outputTokens.reduce((a, b) => a + b, 0);

/**
 * 由序列构建维度树：按步骤为 步骤 → 模型；按模型为 provider/模型 → 步骤。叶子 id 为 leafKey。
 * 一级节点与叶子都按总 token 降序（用量大的在前），同量时按名称排序保证稳定。
 */
export function buildTree(series: UsageSeries[], groupBy: GroupBy): TreeNode[] {
  const groups = new Map<string, { node: TreeNode; total: number; leaves: Array<{ node: TreeNode; total: number }> }>();
  for (const s of series) {
    const groupId = groupBy === "step" ? `g:${s.step}` : `g:${s.provider}|${s.model}`;
    const groupLabel = groupBy === "step" ? s.stepLabel : modelLabel(s);
    const entry = groups.get(groupId) ?? { node: { id: groupId, label: groupLabel, children: [] }, total: 0, leaves: [] };
    const total = tokenTotal(s);
    entry.total += total;
    entry.leaves.push({ node: { id: leafKey(s), label: groupBy === "step" ? modelLabel(s) : s.stepLabel, children: [] }, total });
    groups.set(groupId, entry);
  }
  const order = (a: { node: TreeNode; total: number }, b: { node: TreeNode; total: number }) =>
    b.total - a.total || a.node.label.localeCompare(b.node.label, "zh-Hans-CN");
  return [...groups.values()].sort(order)
    .map(g => ({ ...g.node, children: [...g.leaves].sort(order).map(l => l.node) }));
}

export const allLeafKeys = (tree: TreeNode[]) => tree.flatMap(n => n.children.map(c => c.id));

const sum = (arrays: number[][], length: number) =>
  Array.from({ length }, (_, i) => arrays.reduce((acc, a) => acc + (a[i] ?? 0), 0));

/** 一级节点下全部勾选时合并为一条线，部分勾选时每个勾选叶子一条线。 */
export function buildLines(series: UsageSeries[], tree: TreeNode[], checked: Set<string>, groupBy: GroupBy, metric: Metric): Line[] {
  const byKey = new Map(series.map(s => [leafKey(s), s]));
  const length = series[0] ? metricValues(series[0], metric).length : 0;
  const lines: Line[] = [];
  for (const group of tree) {
    const selected = group.children.filter(c => checked.has(c.id));
    if (selected.length === 0) continue;
    if (selected.length === group.children.length) {
      const leaves = selected.map(c => byKey.get(c.id)!).filter(Boolean);
      lines.push({ name: group.label, values: sum(leaves.map(l => metricValues(l, metric)), length), leaves });
    } else {
      for (const child of selected) {
        const leaf = byKey.get(child.id);
        if (leaf) lines.push({ name: `${group.label} / ${child.label}`, values: [...metricValues(leaf, metric)], leaves: [leaf] });
      }
    }
  }
  return lines;
}

function totalsOf(leaves: UsageSeries[]): Totals {
  const add = (pick: (s: UsageSeries) => number[]) => leaves.reduce((acc, s) => acc + pick(s).reduce((a, b) => a + b, 0), 0);
  const input = add(s => s.inputTokens);
  const output = add(s => s.outputTokens);
  const calls = add(s => s.calls);
  const failures = add(s => s.failures);
  const latency = add(s => s.latencyMs);
  return { total: input + output, input, output, calls, failures,
    failureRate: calls ? failures / calls : 0, avgLatencyMs: calls ? Math.round(latency / calls) : 0 };
}

export const totals = (series: UsageSeries[], checked: Set<string>) => totalsOf(series.filter(s => checked.has(leafKey(s))));

export const tableRows = (lines: Line[]): TableRow[] =>
  lines.map(l => ({ name: l.name, ...totalsOf(l.leaves) })).sort((a, b) => b.total - a.total);

/**
 * 预设时间范围（浏览器本地时区）：右开区间 [from, to)。
 * 以毫秒加减整天，夏令时地区会差 1 小时；本项目面向东八区（无夏令时），不受影响。
 */
export function rangeFor(preset: RangePreset, now = new Date()): { from: Date; to: Date } {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const day = 86_400_000;
  const tomorrow = new Date(today.getTime() + day);
  switch (preset) {
    case "yesterday": return { from: new Date(today.getTime() - day), to: today };
    case "7d": return { from: new Date(tomorrow.getTime() - 7 * day), to: tomorrow };
    case "30d": return { from: new Date(tomorrow.getTime() - 30 * day), to: tomorrow };
    default: return { from: today, to: tomorrow };
  }
}

/**
 * 默认聚焦区间（含两端的桶下标）：取最后一段连续有数据的时段，前后各留 pad 个桶。
 * 零值间隔超过 gap 个桶视为断开（稀疏数据里只看最近一波调用）；全为零时返回全部范围。
 */
export function focusRange(values: number[], gap: number, pad = 2): [number, number] {
  const last = values.length - 1;
  let end = -1;
  for (let i = last; i >= 0; i--) if (values[i]) { end = i; break; }
  if (end < 0) return [0, Math.max(0, last)];
  let start = end;
  for (let i = end - 1; i >= 0 && start - i <= gap; i--) if (values[i]) start = i;
  return [Math.max(0, start - pad), Math.min(last, end + pad)];
}

/** 图表最多展示 max 条维度，其余合并为「其他」，避免颜色超出固定色板后循环复用 */
export function foldLines(lines: Line[], max: number): Line[] {
  if (lines.length <= max) return lines;
  const kept = lines.slice(0, max - 1);
  const rest = lines.slice(max - 1);
  const length = rest[0]?.values.length ?? 0;
  return [...kept, { name: `其他（${rest.length} 项）`, values: sum(rest.map(l => l.values), length), leaves: rest.flatMap(l => l.leaves) }];
}

/** 区间内求和（含两端） */
export const sumRange = (values: number[], [a, b]: [number, number]) => values.slice(a, b + 1).reduce((x, y) => x + y, 0);

/** 桶时间标签：跨天的范围带日期；asEnd 时不带日期的午夜显示为 24:00（区间终点） */
export function bucketLabel(t: number, withDate: boolean, asEnd = false): string {
  const d = new Date(t);
  const pad = (n: number) => String(n).padStart(2, "0");
  if (asEnd && !withDate && d.getHours() === 0 && d.getMinutes() === 0) return "24:00";
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return withDate ? `${d.getMonth() + 1}-${pad(d.getDate())} ${time}` : time;
}
