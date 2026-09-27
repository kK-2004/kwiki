import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import { ApiError, type GrayRelease } from "../src/api";

const mocks = vi.hoisted(() => ({ grayReleases: vi.fn(), parsers: vi.fn(), adminKnowledgeBases: vi.fn(), grayCommand: vi.fn(), createGrayRelease: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
import GrayReleaseView from "../src/views/GrayReleaseView.vue";
import GrayReleaseCreateDialog from "../src/components/GrayReleaseCreateDialog.vue";

const base: GrayRelease = {
  id: 1, name: "pdfbox-v2 灰度 #1", parserVersion: "kwiki-parse-2", parserLabel: "pdfbox-v2", indexVersionNumber: 4,
  physicalName: "kwiki-chunks-v4", status: "SYNCED", lastError: null, createdBy: "admin", createdAt: "2026-09-27T00:00:00Z",
  switchedAt: null, endedAt: null, kbs: [{ kbId: 7, name: "产品文档" }],
  progress: { runId: 9, runState: "COMPLETED", switchState: "READY", scanned: 10, succeeded: 10, failed: 0, pendingTargets: 0 },
  allowedActions: { sync: false, switch: true, switchBack: false, end: true },
};
const plugins = [createPinia(), ElementPlus];

describe("灰度发布页", () => {
  it("按 allowedActions 显示操作按钮，并展示解析器、知识库与进度", async () => {
    mocks.grayReleases.mockResolvedValue([base]);
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("pdfbox-v2 灰度 #1")).toBeTruthy());
    expect(screen.getByText("产品文档")).toBeTruthy();
    expect(screen.getByText("kwiki-chunks-v4")).toBeTruthy();
    expect(screen.getByRole("button", { name: "切换到灰度索引" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "切回原索引" })).toBeNull();
    expect(screen.queryByRole("button", { name: "开始同步" })).toBeNull();
  });

  it("失败原因醒目展示", async () => {
    mocks.grayReleases.mockResolvedValue([{ ...base, status: "SYNCING", lastError: "校验未通过：missingResources=3", allowedActions: { sync: true, switch: false, switchBack: false, end: true } }]);
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("校验未通过：missingResources=3")).toBeTruthy());
    expect(screen.getByRole("button", { name: "开始同步" })).toBeTruthy();
  });

  it("幂等重放的英文错误码转为中文提示，其他错误原样展示", async () => {
    mocks.grayReleases.mockResolvedValue([{ ...base, status: "CREATED", progress: { ...base.progress, runId: null }, allowedActions: { sync: true, switch: false, switchBack: false, end: true } }]);
    mocks.grayCommand.mockRejectedValueOnce(new ApiError(409, "management_command_in_progress")).mockRejectedValueOnce(new ApiError(409, "灰度状态已变化"));
    render(GrayReleaseView, { global: { plugins } });
    const sync = await screen.findByRole("button", { name: "开始同步" });
    await fireEvent.click(sync);
    await waitFor(() => expect(screen.getByText("上一次相同操作仍在进行，请稍后刷新")).toBeTruthy());
    expect(mocks.grayCommand).toHaveBeenCalledWith(1, "sync");
    await fireEvent.click(screen.getByRole("button", { name: "开始同步" }));
    await waitFor(() => expect(screen.getByText("灰度状态已变化")).toBeTruthy());
  });
});

describe("新建灰度弹窗", () => {
  it("搜索知识库；已在其他灰度中的知识库置灰；不可用解析器置灰", async () => {
    mocks.parsers.mockResolvedValue([
      { id: "kwiki-parse-1", label: "tika-v1", available: true, unavailableReason: null },
      { id: "kwiki-parse-2", label: "pdfbox-v2", available: false, unavailableReason: "缺少配置：vision" },
    ]);
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }, { id: 8, name: "技术规范" }, { id: 9, name: "运维手册" }]);
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: { 8: "pdfbox-v2 灰度 #1" } }, global: { plugins } });
    await waitFor(() => expect(screen.getByText("运维手册")).toBeTruthy());
    expect((screen.getByRole("radio", { name: /pdfbox-v2/ }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText("缺少配置：vision")).toBeTruthy();
    expect((screen.getByRole("checkbox", { name: /技术规范/ }) as HTMLInputElement).disabled).toBe(true);
    await fireEvent.update(screen.getByPlaceholderText("搜索知识库"), "运维");
    expect(screen.queryByText("产品文档")).toBeNull();
    expect(screen.getByText("运维手册")).toBeTruthy();
  });
});
