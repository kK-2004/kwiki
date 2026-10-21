import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import { ApiError, type EvalRunStatus, type EvalSummary } from "../src/api";
import { evalErrorMessage, groupRows, layerRows, lossRows, percent, progressPercent } from "../src/evalReport";

const mocks = vi.hoisted(() => ({ adminKnowledgeBases: vi.fn(), evalImportCorpus: vi.fn(), evalSyncDatasets: vi.fn(), evalStartRun: vi.fn(), evalRunStatus: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
const logout = vi.hoisted(() => vi.fn());
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout }) }));
import RagEvalView from "../src/views/RagEvalView.vue";

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

const metric = (coverage: number, fullHit: number, cases = 2) => ({ coverage, fullHit, cases });
const layers = (coverage: number, fullHit: number, cases = 2) => Object.fromEntries(
  ["bm25@50", "vector@50", "pool@50", "fused@8", "fused@20", "context", "answer"].map(key => [key, metric(coverage, fullHit, cases)]));
const summary: EvalSummary = {
  total: 4, errors: 1, scored: 3, overall: layers(0.8, 0.5, 3),
  byCategory: { 事实性问题: layers(0.9, 0.6), 多跳推理问题: layers(0.4, 0.25, 1) },
  byDomain: { Finance: layers(0.7, 0.3, 3) }, bySource: {},
  negatives: 1, refusalAccuracy: 1, lossMatrix: { 事实性问题: { RECALL_MISS: 2, GENERATION_MISS: 1 }, 多跳推理问题: { RANK_MISS_8: 3 } }, contextGains: 2,
};
const running: EvalRunStatus = { runId: "r-1", runName: "baseline-50q", dataset: "50q", state: "RUNNING", total: 50, completed: 10, summary: null, reportPath: null, error: null };
const done: EvalRunStatus = { ...running, state: "COMPLETED", completed: 50, summary, reportPath: "./eval-reports/baseline-50q.json" };
const plugins = [createPinia(), ElementPlus];

describe("evalReport", () => {
  it("把后端错误码翻译成中文，未知错误原样返回", () => {
    expect(evalErrorMessage(new ApiError(200, "langfuse-not-configured"), "x")).toContain("仅本地");
    expect(evalErrorMessage(new ApiError(200, "corpus-not-ready: 12 pages not indexed"), "x")).toBe("评测语料尚未索引完成（还有 12 篇未入索引），请稍后再试");
    expect(evalErrorMessage(new ApiError(200, "corpus-empty"), "x")).toContain("先导入评测语料");
    expect(evalErrorMessage(new ApiError(200, "eval-busy"), "x")).toContain("已有评测正在运行");
    expect(evalErrorMessage(new ApiError(200, "其他错误"), "x")).toBe("其他错误");
    expect(evalErrorMessage(null, "兜底")).toBe("兜底");
  });

  it("格式化百分比与进度", () => {
    expect(percent(0.1234)).toBe("12.3%");
    expect(percent(null)).toBe("-");
    expect(progressPercent({ total: 0, completed: 0 })).toBe(0);
    expect(progressPercent({ total: 3, completed: 1 })).toBe(33);
  });

  it("各层按固定顺序输出，缺失的层为空", () => {
    const rows = layerRows({ "fused@8": metric(0.5, 0.25) });
    expect(rows.map(row => row.key)).toEqual(["bm25@50", "vector@50", "pool@50", "fused@8", "fused@20", "context", "answer"]);
    expect(rows[3]).toMatchObject({ coverage: 0.5, fullHit: 0.25, cases: 2 });
    expect(rows[0].coverage).toBeNull();
  });

  it("分组与丢失层矩阵带合计", () => {
    expect(groupRows({ Finance: layers(0.7, 0.3, 3) }, () => "金融")[0]).toMatchObject({ name: "金融", cases: 3 });
    const { rows, totals } = lossRows(summary.lossMatrix);
    expect(rows[0]).toMatchObject({ category: "事实性问题", total: 3 });
    expect(totals).toMatchObject({ category: "合计", total: 6, counts: { RECALL_MISS: 2, GENERATION_MISS: 1, RANK_MISS_8: 3 } });
  });
});

describe("RAG 评测页", () => {
  it("导入语料显示新建/跳过数量，同步测试集显示条数", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "RAG 评测" }]);
    localStorage.setItem("kwiki_admin_eval_kb", "7");
    mocks.evalImportCorpus.mockResolvedValue({ createdPageIds: [1, 2, 3], skippedTitles: ["悟空-1"] });
    mocks.evalSyncDatasets.mockResolvedValue({ items: 350, written: 20, skipped: 330 });
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(mocks.adminKnowledgeBases).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "导入评测语料" }));
    await waitFor(() => expect(screen.getByText(/语料导入：新建/).textContent).toMatch(/新建 3 篇，跳过（已存在）1 篇/));
    expect(mocks.evalImportCorpus).toHaveBeenCalledWith(7);
    await fireEvent.click(screen.getByRole("button", { name: "同步测试集到 Langfuse" }));
    await waitFor(() => expect(screen.getByText(/条测试题到 Langfuse/).textContent).toMatch(/已同步 350 条.*新写入 20 条.*跳过 330 条/));
  });

  it("知识库列表不可用时允许手动输入 kbId", async () => {
    mocks.adminKnowledgeBases.mockRejectedValue(new ApiError(500, "boom"));
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("未获取到知识库列表，请手动输入 kbId")).toBeTruthy());
    expect(screen.getByRole("button", { name: "导入评测语料" }).hasAttribute("disabled")).toBe(true);
  });

  it("启动评测提交表单并记住 runId，后端错误码显示中文", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "RAG 评测" }]);
    localStorage.setItem("kwiki_admin_eval_kb", "7");
    mocks.evalStartRun.mockRejectedValueOnce(new ApiError(200, "eval-busy")).mockResolvedValueOnce(running);
    mocks.evalRunStatus.mockResolvedValue(running);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(mocks.adminKnowledgeBases).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "开始评测" }));
    await waitFor(() => expect(screen.getByText("已有评测正在运行，同一时刻只允许一个评测")).toBeTruthy());
    await fireEvent.update(screen.getByPlaceholderText(/留空自动生成/), " baseline-50q ");
    await fireEvent.click(screen.getByRole("button", { name: "开始评测" }));
    await waitFor(() => expect(screen.getByText("运行中")).toBeTruthy());
    expect(mocks.evalStartRun).toHaveBeenLastCalledWith({ dataset: "50q", kbId: 7, runName: "baseline-50q", notes: undefined, localOnly: false });
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBe("r-1");
    expect(screen.getByText(/10 \/ 50 题/)).toBeTruthy();
  });

  it("刷新后按记住的 runId 恢复，完成后展示汇总", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValue(done);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("已完成")).toBeTruthy());
    expect(mocks.evalRunStatus).toHaveBeenCalledWith("r-1");
    expect(screen.getByText("./eval-reports/baseline-50q.json")).toBeTruthy();
    expect(screen.getByText("负样本拒答正确率")).toBeTruthy();
    // el-table 在下一帧才渲染行
    expect((await screen.findAllByText("fused@20")).length).toBeGreaterThan(0);
    await waitFor(() => expect(screen.getAllByText("多跳推理问题").length).toBe(2));
    expect(screen.getAllByText("合计").length).toBeGreaterThan(1);
    expect(screen.getAllByText("80.0%").length).toBeGreaterThan(0);
  });

  it("运行中按间隔轮询直到完成", async () => {
    vi.useFakeTimers();
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValueOnce(running).mockResolvedValue(done);
    render(RagEvalView, { global: { plugins } });
    await vi.waitFor(() => expect(mocks.evalRunStatus).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(3000);
    expect(mocks.evalRunStatus).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10000);
    expect(mocks.evalRunStatus).toHaveBeenCalledTimes(2);
  });

  it("记住的 runId 查不到时提示已失效，可清除", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "gone");
    mocks.evalRunStatus.mockRejectedValue(new ApiError(200, "eval-run-not-found"));
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("评测记录已失效")).toBeTruthy());
    await fireEvent.click(screen.getByRole("button", { name: "清除记录" }));
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBeNull();
    expect(screen.queryByText("评测记录已失效")).toBeNull();
  });
});
