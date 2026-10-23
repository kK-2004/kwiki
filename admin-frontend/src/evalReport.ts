import type { EvalLayerMetric, EvalRunStatus, EvalSummary } from "./api";

/** 评测分层，顺序与后端 EvalLayer 一致：从单路候选池到最终回答。 */
export const EVAL_LAYERS = [
  { key: "bm25@50", label: "BM25 Top-50", hint: "关键词检索单路候选" },
  { key: "vector@50", label: "向量 Top-50", hint: "向量检索单路候选" },
  { key: "pool@50", label: "候选池", hint: "两路 Top-50 合并" },
  { key: "fused@8", label: "融合 Top-8", hint: "基础阶段" },
  { key: "fused@20", label: "融合 Top-20", hint: "扩大阶段" },
  { key: "context", label: "最终上下文", hint: "完整工作流送入生成的片段" },
  { key: "answer", label: "回答", hint: "按回答全文匹配" },
] as const;

/** 首次丢失层，顺序按链路从后往前，与后端 LossLayer 一致。 */
export const LOSS_LAYERS = [
  { key: "GENERATION_MISS", label: "生成遗漏", hint: "上下文有、回答没写出" },
  { key: "RECALL_MISS", label: "召回遗漏", hint: "两路 Top-50 都没有" },
  { key: "RANK_MISS_20", label: "排序遗漏@20", hint: "在候选池但没进 Top-20" },
  { key: "RANK_MISS_8", label: "排序遗漏@8", hint: "在 Top-20 但没进 Top-8" },
  { key: "CONTEXT_MISS", label: "上下文遗漏", hint: "在 Top-8 却没进最终上下文" },
] as const;

export const EVAL_DATASETS = [
  { value: "50q", label: "50 题" },
  { value: "100q", label: "100 题" },
  { value: "200q", label: "200 题" },
] as const;

export const RUN_STATE_LABELS: Record<EvalRunStatus["state"], { label: string; type: "primary" | "success" | "warning" | "danger" | "info" }> = {
  QUEUED: { label: "排队中", type: "info" },
  RUNNING: { label: "运行中", type: "primary" },
  COMPLETED: { label: "已完成", type: "success" },
  REPORT_PARTIAL: { label: "完成（上报不完整）", type: "warning" },
  CANCELLED: { label: "已中断", type: "warning" },
  FAILED: { label: "失败", type: "danger" },
};

/** 排队中 / 运行中的 run 可中断（排队中直接取消）；中断、失败的 run 可继续；非运行中的 run 可删除；有已完成题即可下载报告。 */
export const canCancel = (run: Pick<EvalRunStatus, "state">) => run.state === "RUNNING" || run.state === "QUEUED";
export const canResume = (run: Pick<EvalRunStatus, "state">) => run.state === "CANCELLED" || run.state === "FAILED";
export const canDelete = (run: Pick<EvalRunStatus, "state">) => run.state !== "RUNNING";
/** 已结束（含中断、失败）且有出错题的 run 可重试出错题。 */
export const canRetryFailed = (run: Pick<EvalRunStatus, "state" | "summary">) =>
  run.state !== "RUNNING" && run.state !== "QUEUED" && (run.summary?.errors ?? 0) > 0;
export const canDownload = (run: Pick<EvalRunStatus, "completed">) => run.completed > 0;

/** run 的 error 字段（稳定码）→ 中文说明。 */
export function runErrorMessage(error: string | null | undefined): string | null {
  if (!error) return null;
  if (error === "cancelled") return "已手动中断：已完成的题已保存，可继续评测剩余题目";
  if (error === "interrupted") return "运行中断（服务重启或实例失联）：已完成的题已保存，可继续评测剩余题目";
  if (error === "eval-busy") return "已有评测正在运行，本次未执行";
  if (error === "submitter-not-authorized") return "评测提交人已不是启用的管理员，无法执行；请由管理员重新提交";
  return error;
}

export function formatTime(value: string | null | undefined) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 把报告 JSON 作为文件下载；文件名中的非法字符替换为下划线。 */
export function downloadJson(name: string, data: unknown) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${name.replace(/[^\w.\-\u4e00-\u9fa5]/g, "_")}.json`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

const DOMAIN_LABELS: Record<string, string> = { Finance: "金融", Law: "法律", Medical: "医疗" };
export const domainLabel = (domain: string) => DOMAIN_LABELS[domain] ?? domain;

/** 后端稳定错误码（可能带「: 详情」后缀）→ 中文提示。 */
export function evalErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof Error) || !error.message) return fallback;
  const raw = error.message;
  const [code, ...rest] = raw.split(":");
  const detail = rest.join(":").trim();
  switch (code.trim()) {
    case "langfuse-not-configured":
      return "未配置 Langfuse（kwiki.langfuse.public-key / secret-key），请在配置中心补齐，或勾选「仅本地」运行";
    case "corpus-not-ready": {
      const pending = /^(\d+)/.exec(detail)?.[1];
      return pending ? `评测语料尚未索引完成（还有 ${pending} 篇未入索引），请稍后再试` : "评测语料尚未索引完成，请稍后再试";
    }
    case "corpus-empty":
      return "该知识库还没有评测语料，请先导入评测语料";
    case "eval-busy":
      return "已有评测正在运行，同一时刻只允许一个评测";
    case "eval-run-not-found":
      return "评测记录不存在（可能已被删除）";
    case "eval-run-not-running":
      return "该评测已不在运行中";
    case "eval-run-not-resumable":
      return "只有已中断或失败的评测可以继续";
    case "eval-run-not-retryable":
      return "排队中或运行中的评测不能重试，请等待结束";
    case "eval-run-no-failed-cases":
      return "该评测没有出错的题目";
    case "eval-run-running":
      return "运行中的评测不能删除，请先中断";
    case "eval-dataset-empty":
      return "测试集为空，请检查数据集文件";
    case "unknown-dataset":
      return "未知的测试集";
    case "kbId-required":
      return "请选择知识库";
    case "corpus-not-found":
      return "未找到评测语料";
    default:
      return raw;
  }
}

export const percent = (value: number | null | undefined) =>
  value === null || value === undefined || Number.isNaN(value) ? "-" : `${(value * 100).toFixed(1)}%`;

export function progressPercent(run: Pick<EvalRunStatus, "total" | "completed">) {
  if (!run.total) return 0;
  return Math.max(0, Math.min(100, Math.round(run.completed * 100 / run.total)));
}

export interface LayerRow { key: string; label: string; hint: string; coverage: number | null; fullHit: number | null; cases: number }

/** 汇总各层指标为表格行；后端缺失的层显示为空。 */
export function layerRows(metrics: Record<string, EvalLayerMetric> | undefined): LayerRow[] {
  return EVAL_LAYERS.map(layer => {
    const metric = metrics?.[layer.key];
    return { ...layer, coverage: metric?.coverage ?? null, fullHit: metric?.fullHit ?? null, cases: metric?.cases ?? 0 };
  });
}

export interface GroupRow { name: string; cases: number; metrics: Record<string, EvalLayerMetric> }

/** 分组（题型/领域）→ 表格行，每行携带该组各层指标。 */
export function groupRows(groups: Record<string, Record<string, EvalLayerMetric>> | undefined, label: (name: string) => string = name => name): GroupRow[] {
  return Object.entries(groups ?? {}).map(([name, metrics]) => ({
    name: label(name),
    cases: Object.values(metrics)[0]?.cases ?? 0,
    metrics,
  }));
}

export interface LossRow { category: string; counts: Record<string, number>; total: number }

/** 丢失层矩阵：题型 × 丢失层，附行合计与列合计行。 */
export function lossRows(matrix: EvalSummary["lossMatrix"] | undefined): { rows: LossRow[]; totals: LossRow } {
  const rows = Object.entries(matrix ?? {}).map(([category, counts]) => ({
    category,
    counts,
    total: Object.values(counts).reduce((sum, count) => sum + count, 0),
  }));
  const counts: Record<string, number> = {};
  for (const row of rows) for (const [key, count] of Object.entries(row.counts)) counts[key] = (counts[key] ?? 0) + count;
  return { rows, totals: { category: "合计", counts, total: rows.reduce((sum, row) => sum + row.total, 0) } };
}
