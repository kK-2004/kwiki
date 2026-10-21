import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";

const mocks = vi.hoisted(() => ({ modelUsageSeries: vi.fn(), modelUsageUsers: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
vi.mock("vue-echarts", () => ({ default: { name: "VChart", props: ["option"], template: "<div data-testid='chart'>{{ JSON.stringify(option.series.map(s => s.name)) }}</div>" } }));
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
import ModelUsageView from "../src/views/ModelUsageView.vue";

const series = (step: string, stepLabel: string, model: string, input: number[]) => ({
  step, stepLabel, provider: "dashscope", model, calls: input.map(() => 1), failures: input.map(() => 0),
  inputTokens: input, outputTokens: input.map(() => 0), latencyMs: input.map(() => 10),
});
const plugins = [createPinia(), ElementPlus];

beforeEach(() => {
  mocks.modelUsageUsers.mockResolvedValue([{ userId: 7, displayName: "张三", totalTokens: 12_300 }, { userId: 0, displayName: "系统", totalTokens: 100 }]);
  mocks.modelUsageSeries.mockResolvedValue({
    bucketSeconds: 300, buckets: [Date.parse("2026-10-20T16:00:00Z"), Date.parse("2026-10-20T16:05:00Z")],
    series: [series("ANSWER", "回答生成", "qwen-plus", [12_000, 300]), series("REWRITE", "查询改写", "qwen-plus", [5, 5])],
  });
});
afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); });

describe("模型用量页", () => {
  it("默认查询今天并显示自适应单位的汇总", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalled());
    const query = mocks.modelUsageSeries.mock.calls[0][0];
    expect(new Date(query.to).getTime() - new Date(query.from).getTime()).toBe(86_400_000);
    expect(new Date(query.from).getHours()).toBe(0);
    expect(query.tzOffsetMinutes).toBe(-new Date().getTimezoneOffset());
    await waitFor(() => expect(screen.getByTestId("total-tokens").textContent).toBe("12.3k"));
  });

  it("按步骤默认每个步骤一条线，切换按模型后按模型分线", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toContain("回答生成"));
    expect(screen.getByTestId("chart").textContent).toContain("查询改写");
    await fireEvent.click(screen.getByRole("radio", { name: "按模型" }));
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toContain("dashscope / qwen-plus"));
  });

  it("切换来源会带着来源重新查询", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalledTimes(1));
    await fireEvent.click(screen.getByRole("checkbox", { name: "评测" }));
    await waitFor(() => expect(mocks.modelUsageSeries).toHaveBeenCalledTimes(2));
    expect(mocks.modelUsageSeries.mock.calls[1][0].sources).toEqual(["ONLINE", "BACKGROUND"]);
  });

  it("刷新保留用户取消勾选的维度，新出现的叶子默认勾选", async () => {
    render(ModelUsageView, { global: { plugins } });
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toContain("回答生成"));
    // 树复选框 UI 比数据晚一拍渲染：等「回答生成」下的模型叶子（第 5 个复选框）同步为选中后再点击取消
    await waitFor(() => expect((screen.getAllByRole("checkbox")[4] as HTMLInputElement).checked).toBe(true));
    await fireEvent.click(screen.getAllByRole("checkbox")[4]);
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toBe(JSON.stringify(["查询改写"])));
    // 触发第二次查询，返回值多出一个新步骤
    mocks.modelUsageSeries.mockResolvedValue({
      bucketSeconds: 300, buckets: [Date.parse("2026-10-20T16:00:00Z"), Date.parse("2026-10-20T16:05:00Z")],
      series: [series("ANSWER", "回答生成", "qwen-plus", [12_000, 300]), series("REWRITE", "查询改写", "qwen-plus", [5, 5]), series("EMBEDDING", "索引嵌入", "text-embedding-v3", [500, 500])],
    });
    await fireEvent.click(screen.getByRole("checkbox", { name: "评测" }));
    // 「回答生成」保持取消勾选不被重置，「索引嵌入」作为新叶子默认勾选
    await waitFor(() => expect(screen.getByTestId("chart").textContent).toBe(JSON.stringify(["索引嵌入", "查询改写"])));
    expect(screen.getByTestId("chart").textContent).not.toContain("回答生成");
  });
});
