import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import { ApiError, type EvalRunStatus, type EvalSummary } from "../src/api";
import { canCancel, canDelete, canDownload, canResume, canRetryFailed, evalErrorMessage, groupRows, layerRows, lossRows, percent, progressPercent, runErrorMessage } from "../src/evalReport";

const mocks = vi.hoisted(() => ({
  adminKnowledgeBases: vi.fn(), evalImportCorpus: vi.fn(), evalSyncDatasets: vi.fn(), evalStartRun: vi.fn(), evalRunStatus: vi.fn(),
  evalListRuns: vi.fn(), evalCancelRun: vi.fn(), evalResumeRun: vi.fn(), evalRetryFailedRun: vi.fn(), evalDeleteRun: vi.fn(), evalRunReport: vi.fn(),
}));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
const logout = vi.hoisted(() => vi.fn());
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout }) }));
import RagEvalView from "../src/views/RagEvalView.vue";

beforeEach(() => { mocks.evalListRuns.mockResolvedValue([]); });
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); localStorage.clear(); });

const metric = (coverage: number, fullHit: number, cases = 2) => ({ coverage, fullHit, cases });
const layers = (coverage: number, fullHit: number, cases = 2) => Object.fromEntries(
  ["bm25@50", "vector@50", "pool@50", "fused@8", "fused@20", "context", "answer"].map(key => [key, metric(coverage, fullHit, cases)]));
const summary: EvalSummary = {
  total: 4, errors: 1, scored: 3, overall: layers(0.8, 0.5, 3),
  byCategory: { 事实性问题: layers(0.9, 0.6), 多跳推理问题: layers(0.4, 0.25, 1) },
  byDomain: { Finance: layers(0.7, 0.3, 3) }, bySource: {},
  negatives: 1, refusalAccuracy: 1, lossMatrix: { 事实性问题: { RECALL_MISS: 2, GENERATION_MISS: 1 }, 多跳推理问题: { RANK_MISS_8: 3 } }, contextGains: 2,
};
const running: EvalRunStatus = { runId: "r-1", runName: "baseline-50q", dataset: "50q", state: "RUNNING", total: 50, completed: 10, summary: null, error: null, localOnly: false, reportPartial: false, createdAt: "2026-10-21T02:00:00Z", ownerInstanceId: "host-a-1234-abcd", queuePosition: null };
const done: EvalRunStatus = { ...running, state: "COMPLETED", completed: 50, summary };
const cancelled: EvalRunStatus = { ...running, state: "CANCELLED", completed: 12, error: "cancelled" };
const queued: EvalRunStatus = { ...running, state: "QUEUED", completed: 0, ownerInstanceId: null, queuePosition: 2, summary: null, error: null };
const plugins = [createPinia(), ElementPlus];

describe("evalReport", () => {
  it("把后端错误码翻译成中文，未知错误原样返回", () => {
    expect(evalErrorMessage(new ApiError(200, "langfuse-not-configured"), "x")).toContain("仅本地");
    expect(evalErrorMessage(new ApiError(200, "corpus-not-ready: 12 pages not indexed"), "x")).toBe("评测语料尚未索引完成（还有 12 篇未入索引），请稍后再试");
    expect(evalErrorMessage(new ApiError(200, "corpus-empty"), "x")).toContain("先导入评测语料");
    expect(evalErrorMessage(new ApiError(200, "eval-busy"), "x")).toContain("已有评测正在运行");
    expect(evalErrorMessage(new ApiError(200, "其他错误"), "x")).toBe("其他错误");
    expect(evalErrorMessage(null, "兜底")).toBe("兜底");
    expect(evalErrorMessage(new ApiError(409, "eval-run-running"), "x")).toContain("请先中断");
    expect(evalErrorMessage(new ApiError(409, "eval-run-not-resumable"), "x")).toContain("已中断或失败");
    expect(evalErrorMessage(new ApiError(409, "eval-run-no-failed-cases"), "x")).toContain("没有出错");
    expect(runErrorMessage("interrupted")).toContain("服务重启");
    expect(runErrorMessage("cancelled")).toContain("手动中断");
    expect(runErrorMessage("submitter-not-authorized")).toContain("管理员");
    expect(runErrorMessage(null)).toBeNull();
  });

  it("按状态决定可用操作", () => {
    expect([canCancel(running), canResume(running), canDelete(running)]).toEqual([true, false, false]);
    expect([canCancel(queued), canResume(queued), canDelete(queued)]).toEqual([true, false, true]);
    expect([canCancel(cancelled), canResume(cancelled), canDelete(cancelled)]).toEqual([false, true, true]);
    expect(canResume({ state: "FAILED" })).toBe(true);
    expect(canResume(done)).toBe(false);
    expect(canDownload({ completed: 0 })).toBe(false);
    const withErrors = { ...summary, errors: 2 };
    expect(canRetryFailed({ ...done, summary: withErrors })).toBe(true);
    expect(canRetryFailed({ ...cancelled, summary: withErrors })).toBe(true);
    expect(canRetryFailed({ ...running, summary: withErrors })).toBe(false);
    expect(canRetryFailed({ ...queued, summary: withErrors })).toBe(false);
    expect(canRetryFailed({ ...done, summary: { ...summary, errors: 0 } })).toBe(false);
    expect(canRetryFailed({ ...cancelled, summary: null })).toBe(false);
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
    mocks.evalStartRun.mockRejectedValueOnce(new ApiError(200, "eval-busy")).mockResolvedValueOnce(queued);
    mocks.evalRunStatus.mockResolvedValue(queued);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(mocks.adminKnowledgeBases).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "开始评测" }));
    await waitFor(() => expect(screen.getByText("已有评测正在运行，同一时刻只允许一个评测")).toBeTruthy());
    await fireEvent.update(screen.getByPlaceholderText(/留空自动生成/), " baseline-50q ");
    await fireEvent.click(screen.getByRole("button", { name: "开始评测" }));
    await waitFor(() => expect(screen.getByText("排队中")).toBeTruthy());
    expect(mocks.evalStartRun).toHaveBeenLastCalledWith({ dataset: "50q", kbId: 7, runName: "baseline-50q", notes: undefined, localOnly: false });
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBe("r-1");
    expect(screen.getByText(/0 \/ 50 题/)).toBeTruthy();
    expect(await screen.findByText("已加入队列，等待执行")).toBeTruthy();
  });

  it("已有评测在执行时仍可开始新评测（进入队列）", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "RAG 评测" }]);
    localStorage.setItem("kwiki_admin_eval_kb", "7");
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValue(running);
    mocks.evalStartRun.mockResolvedValue(queued);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("运行中")).toBeTruthy());
    const button = screen.getByRole("button", { name: "开始评测" }) as HTMLButtonElement;
    expect(button.hasAttribute("disabled")).toBe(false);
    await fireEvent.click(button);
    await waitFor(() => expect(mocks.evalStartRun).toHaveBeenCalled());
  });

  it("排队中的评测显示位次且可中断，运行中显示执行实例", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValue([queued, running]);
    render(RagEvalView, { global: { plugins } });
    await screen.findAllByText("baseline-50q");
    expect(screen.getByText("排队中")).toBeTruthy();
    expect(screen.getByText(/前面还有 2 个/)).toBeTruthy();
    expect(screen.getByText("host-a-1234-abcd")).toBeTruthy();
    // 排队中的中断确认文案与运行中不同
    await fireEvent.click(screen.getAllByRole("button", { name: "中断" })[0]);
    await screen.findByText(/该评测仍在排队，取消后可随时继续/);
    expect(mocks.evalCancelRun).not.toHaveBeenCalled();
  });

  it("运行中的评测显示执行实例标识", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValue(running);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("执行实例 host-a-1234-abcd")).toBeTruthy());
  });

  it("刷新后按记住的 runId 恢复，完成后展示汇总", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValue(done);
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("已完成")).toBeTruthy());
    expect(mocks.evalRunStatus).toHaveBeenCalledWith("r-1");
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

  it("历史列表中当前查看的评测随轮询实时更新进度", async () => {
    vi.useFakeTimers();
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalListRuns.mockResolvedValue([running]);
    mocks.evalRunStatus.mockResolvedValueOnce(running).mockResolvedValue({ ...running, completed: 25 });
    render(RagEvalView, { global: { plugins } });
    await vi.waitFor(() => expect(screen.getAllByText("10 / 50").length).toBeGreaterThan(0));

    await vi.advanceTimersByTimeAsync(3000);

    await vi.waitFor(() => expect(screen.getAllByText("25 / 50").length).toBeGreaterThan(0));
    expect(screen.queryByText("10 / 50")).toBeNull();
  });

  it("有排队或运行中的评测时历史列表自动刷新，全部结束后停止", async () => {
    vi.useFakeTimers();
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    const other = { ...queued, runId: "r-2", runName: "other-50q" };
    mocks.evalListRuns
      .mockResolvedValueOnce([other])
      .mockResolvedValueOnce([{ ...other, state: "RUNNING", queuePosition: null, completed: 3 }])
      .mockResolvedValue([{ ...other, state: "COMPLETED", queuePosition: null, completed: 50 }]);
    render(RagEvalView, { global: { plugins } });
    await vi.waitFor(() => expect(screen.getByText(/前面还有 2 个/)).toBeTruthy());

    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(screen.getByText("3 / 50")).toBeTruthy());
    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(screen.getByText("50 / 50")).toBeTruthy());
    const calls = mocks.evalListRuns.mock.calls.length;

    await vi.advanceTimersByTimeAsync(20000);
    expect(mocks.evalListRuns.mock.calls.length).toBe(calls);
  });

  it("记住的 runId 查不到时提示已失效，可清除", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "gone");
    mocks.evalRunStatus.mockRejectedValue(new ApiError(200, "eval-run-not-found"));
    render(RagEvalView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("评测记录不存在")).toBeTruthy());
    await fireEvent.click(screen.getByRole("button", { name: "清除记录" }));
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBeNull();
    expect(screen.queryByText("评测记录不存在")).toBeNull();
  });

  it("历史列表：中断运行中的评测需确认", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValueOnce([running]).mockResolvedValue([cancelled]);
    mocks.evalCancelRun.mockResolvedValue(cancelled);
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("baseline-50q");
    expect(screen.queryByRole("button", { name: "删除" })).toBeNull();
    await fireEvent.click(screen.getByRole("button", { name: "中断" }));
    await screen.findByText(/正在执行的题会立即取消/);
    expect(mocks.evalCancelRun).not.toHaveBeenCalled();
    await fireEvent.click(screen.getByRole("button", { name: "确定中断" }));
    await waitFor(() => expect(mocks.evalCancelRun).toHaveBeenCalledWith("r-1"));
    await waitFor(() => expect(screen.getByRole("button", { name: "继续" })).toBeTruthy());
  });

  it("继续已中断的评测后开始轮询该 run", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValue([cancelled]);
    mocks.evalResumeRun.mockResolvedValue({ ...cancelled, state: "RUNNING", error: null });
    mocks.evalRunStatus.mockResolvedValue(done);
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("baseline-50q");
    await fireEvent.click(screen.getByRole("button", { name: "继续" }));
    await screen.findByText(/只评测尚未完成的 38 题/);
    await fireEvent.click(screen.getByRole("button", { name: "确定继续" }));
    await waitFor(() => expect(mocks.evalResumeRun).toHaveBeenCalledWith("r-1"));
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBe("r-1");
    await waitFor(() => expect(screen.getAllByText("运行中").length).toBeGreaterThan(0));
  });

  it("重试出错题后重新排队并轮询该 run", async () => {
    const failed: EvalRunStatus = { ...done, summary: { ...summary, errors: 4 } };
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValue([failed]);
    mocks.evalRetryFailedRun.mockResolvedValue({ ...failed, state: "QUEUED", completed: 46, queuePosition: 0 });
    mocks.evalRunStatus.mockResolvedValue({ ...failed, state: "RUNNING", completed: 46 });
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("baseline-50q");
    await fireEvent.click(screen.getByRole("button", { name: "重试出错题" }));
    await screen.findByText(/的 4 道出错题/);
    await fireEvent.click(screen.getByRole("button", { name: "确定重试" }));
    await waitFor(() => expect(mocks.evalRetryFailedRun).toHaveBeenCalledWith("r-1"));
    expect(localStorage.getItem("kwiki_admin_eval_run")).toBe("r-1");
  });

  it("删除时可选择是否同时删除 Langfuse 数据，失败给出提示", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    localStorage.setItem("kwiki_admin_eval_run", "r-1");
    mocks.evalRunStatus.mockResolvedValue(cancelled);
    mocks.evalListRuns.mockResolvedValueOnce([cancelled]).mockResolvedValue([]);
    mocks.evalDeleteRun.mockResolvedValue({ langfuseDeleted: false, langfuseError: "langfuse down" });
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("已手动中断：已完成的题已保存，可继续评测剩余题目");
    await fireEvent.click(screen.getAllByRole("button", { name: "删除" })[0]);
    const checkbox = await screen.findByRole("checkbox", { name: "同时删除 Langfuse 上的 trace 与 dataset run" });
    expect((checkbox as HTMLInputElement).checked).toBe(true);
    await fireEvent.click(checkbox);
    await fireEvent.click(checkbox);
    await fireEvent.click(screen.getByRole("button", { name: "确定删除" }));
    await waitFor(() => expect(mocks.evalDeleteRun).toHaveBeenCalledWith("r-1", true));
    await waitFor(() => expect(localStorage.getItem("kwiki_admin_eval_run")).toBeNull());
    await screen.findByText(/Langfuse 删除失败：langfuse down/);
  });

  it("仅本地的评测删除时不能勾选 Langfuse", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValue([{ ...done, localOnly: true }]);
    mocks.evalDeleteRun.mockResolvedValue({ langfuseDeleted: false, langfuseError: null });
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("baseline-50q");
    await fireEvent.click(screen.getByRole("button", { name: "删除" }));
    const checkbox = await screen.findByRole("checkbox", { name: "同时删除 Langfuse 上的 trace 与 dataset run" });
    expect((checkbox as HTMLInputElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "确定删除" }));
    await waitFor(() => expect(mocks.evalDeleteRun).toHaveBeenCalledWith("r-1", false));
  });

  it("下载 JSON 从服务端取报告并生成文件", async () => {
    mocks.adminKnowledgeBases.mockResolvedValue([]);
    mocks.evalListRuns.mockResolvedValue([done]);
    mocks.evalRunReport.mockResolvedValue({ runName: "baseline-50q", cases: [] });
    const createObjectURL = vi.fn(() => "blob:report");
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    render(RagEvalView, { global: { plugins } });
    await screen.findByText("baseline-50q");
    await fireEvent.click(screen.getByRole("button", { name: "下载 JSON" }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(mocks.evalRunReport).toHaveBeenCalledWith("r-1");
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe("baseline-50q.json");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:report");
  });
});
