import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus, { ElMessageBox } from "element-plus";
import { ApiError, type GrayRelease } from "../src/api";

const mocks = vi.hoisted(() => ({ versions: vi.fn(), plugins: vi.fn(), grayReleases: vi.fn(), parsers: vi.fn(), adminKnowledgeBases: vi.fn(), grayCommand: vi.fn(), createGrayRelease: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
const logout = vi.hoisted(() => vi.fn());
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout }) }));
import GrayReleaseView from "../src/views/GrayReleaseView.vue";
import GrayReleaseCreateDialog from "../src/components/GrayReleaseCreateDialog.vue";
import GrayReleaseCard from "../src/components/GrayReleaseCard.vue";

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

const base: GrayRelease = {
  id: 1, name: "pdfbox-v2 灰度 #1", parserVersion: "kwiki-parse-2", parserLabel: "pdfbox-v2", indexVersionNumber: 4,
  physicalName: "kwiki-chunks-v4", status: "SYNCED", lastError: null, createdBy: "admin", createdAt: "2026-09-27T00:00:00Z",
  switchedAt: null, endedAt: null, kbs: [{ kbId: 7, name: "产品文档" }],
  progress: { runId: 9, runState: "COMPLETED", switchState: "READY", scanned: 10, succeeded: 10, failed: 0, migrationPending: 0, pendingTargets: 0 },
  allowedActions: { sync: false, switch: true, switchBack: false, end: true },
};
const plugins = [createPinia(), ElementPlus];
beforeEach(()=>{mocks.versions.mockResolvedValue([{versionNumber:4,selected:true,indexSchemaLabel:"实体格式",configuration:{indexSchema:"modern",chunkerVersion:"c",parserVersion:"kwiki-parse-2"}}]);mocks.plugins.mockImplementation(async()=>({schemas:[{id:"modern",label:"实体格式",available:true,capabilities:["RESOURCE_IDS"],unavailableReason:null}],chunkers:[{id:"c",label:"分块器",available:true,unavailableReason:null}],parsers:await mocks.parsers(),defaults:{indexSchema:"modern"}}))});

describe("灰度发布页", () => {
  it("按 allowedActions 显示操作按钮，并展示解析器、知识库与进度", async () => {
    mocks.grayReleases.mockResolvedValue([base]);
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("pdfbox-v2 灰度 #1")).toBeTruthy());
    expect(screen.getByText("产品文档")).toBeTruthy();
    expect(screen.getByText(/字段格式.*实体格式/)).toBeTruthy();
    expect(screen.getByText("kwiki-chunks-v4")).toBeTruthy();
    expect(screen.getByText("存量迁移：已排队 10 · 已完成 10 · 处理中 0 · 失败 0 ｜ 双写积压 0")).toBeTruthy();
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

  it("卸载时请求在途，返回后也不再续期轮询", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    let resolve!: (value: GrayRelease[]) => void;
    mocks.grayReleases.mockReturnValue(new Promise<GrayRelease[]>(done => { resolve = done; }));
    const { unmount } = render(GrayReleaseView, { global: { plugins } });
    expect(mocks.grayReleases).toHaveBeenCalledTimes(1);
    unmount();
    resolve([base]);
    await vi.advanceTimersByTimeAsync(120000);
    expect(mocks.grayReleases).toHaveBeenCalledTimes(1);
  });

  it("会话失效（401）时退出登录", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    mocks.grayReleases.mockRejectedValue(new ApiError(401, "unauthorized"));
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(logout).toHaveBeenCalled());
    expect(assign).toHaveBeenCalledWith("/admin/login");
  });
});

describe("灰度卡片确认", () => {
  it("取消确认不发出操作，确认后发出", async () => {
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel").mockResolvedValueOnce("confirm" as never);
    const { emitted } = render(GrayReleaseCard, { props: { release: base, busy: false }, global: { plugins } });
    await fireEvent.click(screen.getByRole("button", { name: "切换到灰度索引" }));
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
    expect(emitted().command).toBeUndefined();
    await fireEvent.click(screen.getByRole("button", { name: "切换到灰度索引" }));
    await waitFor(() => expect(emitted().command).toEqual([["switch"]]));
  });
});

describe("新建灰度弹窗", () => {
  it.each([true, false])("线上 parse-1 在 parse-2 可用性为 %s 时仍作为三项默认值", async available => {
    mocks.versions.mockResolvedValue([{ selected: true, kbScoped: false, configuration: { parserVersion: "kwiki-parse-1", indexSchema: "online-schema", chunkerVersion: "online-chunker" } }]);
    mocks.plugins.mockResolvedValue({
      parsers: [
        { id: "kwiki-parse-1", label: "线上解析器", available: true, requiredCapabilities: [] },
        { id: "kwiki-parse-2", label: "备选解析器", available, requiredCapabilities: [] },
      ],
      schemas: [
        { id: "catalog-schema", label: "目录格式", available: true, capabilities: [] },
        { id: "online-schema", label: "线上格式", available: true, capabilities: [] },
        { id: "disabled-schema", label: "停用格式", available: false, unavailableReason: "格式停用", capabilities: [] },
      ],
      chunkers: [
        { id: "catalog-chunker", label: "目录分块器", available: true },
        { id: "online-chunker", label: "线上分块器", available: true },
        { id: "disabled-chunker", label: "停用分块器", available: false, unavailableReason: "分块器停用" },
      ],
      defaults: { indexSchema: "catalog-schema" },
    });
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }]);
    mocks.createGrayRelease.mockResolvedValue({ id: 2, status: "CREATED" });
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: {} }, global: { plugins } });
    await fireEvent.click(await screen.findByRole("checkbox", { name: /产品文档/ }));
    expect((screen.getByRole("radio", { name: /线上解析器/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("radio", { name: /备选解析器/ }) as HTMLInputElement).disabled).toBe(!available);
    expect(screen.getByRole("combobox", { name: "字段格式" }).closest(".el-select")?.textContent).toContain("线上格式（online-schema）");
    expect(screen.getByRole("combobox", { name: "分块器" }).closest(".el-select")?.textContent).toContain("线上分块器（online-chunker）");
    await fireEvent.click(screen.getByRole("button", { name: "创建灰度" }));
    await waitFor(() => expect(mocks.createGrayRelease).toHaveBeenCalledWith({ name: undefined, parserVersion: "kwiki-parse-1", indexSchema: "online-schema", chunkerVersion: "online-chunker", kbIds: [7] }));
    for (const [label, option, disabled] of [["字段格式", "目录格式（catalog-schema）", "停用格式（disabled-schema）"], ["分块器", "目录分块器（catalog-chunker）", "停用分块器（disabled-chunker）"]]) {
      await fireEvent.click(screen.getByRole("combobox", { name: label }));
      const disabledOption = await screen.findByRole("option", { name: new RegExp(disabled.replace(/[()]/g, "\\$&")) });
      expect(disabledOption.getAttribute("aria-disabled")).toBe("true");
      await fireEvent.click(await screen.findByRole("option", { name: option }));
    }
    await fireEvent.click(screen.getByRole("button", { name: "创建灰度" }));
    await waitFor(() => expect(mocks.createGrayRelease).toHaveBeenLastCalledWith({ name: undefined, parserVersion: "kwiki-parse-1", indexSchema: "catalog-schema", chunkerVersion: "catalog-chunker", kbIds: [7] }));
  });

  it("没有全局线上版本时才使用目录字段格式默认值", async () => {
    mocks.versions.mockResolvedValue([{ selected: true, kbScoped: true, configuration: { parserVersion: "scoped-parser", indexSchema: "scoped-schema", chunkerVersion: "scoped-chunker" } }]);
    mocks.parsers.mockResolvedValue([{ id: "kwiki-parse-1", label: "tika-v1", available: true, requiredCapabilities: [] }]);
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }]);
    mocks.createGrayRelease.mockResolvedValue({ id: 2, status: "CREATED" });
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: {} }, global: { plugins } });
    await fireEvent.click(await screen.findByRole("checkbox", { name: /产品文档/ }));
    await fireEvent.click(screen.getByRole("button", { name: "创建灰度" }));
    await waitFor(() => expect(mocks.createGrayRelease).toHaveBeenCalledWith({ name: undefined, parserVersion: "kwiki-parse-1", indexSchema: "modern", chunkerVersion: "c", kbIds: [7] }));
  });

  it("搜索知识库；已在其他灰度中的知识库置灰；不可用解析器置灰", async () => {
    mocks.parsers.mockResolvedValue([
      { id: "kwiki-parse-1", label: "tika-v1", available: true, unavailableReason: null, requiredCapabilities: [] },
      { id: "kwiki-parse-2", label: "pdfbox-v2", available: false, unavailableReason: "缺少配置：vision", requiredCapabilities: [] },
    ]);
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }, { id: 8, name: "技术规范" }, { id: 9, name: "运维手册" }]);
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: { 8: "pdfbox-v2 灰度 #1" } }, global: { plugins } });
    await waitFor(() => expect(screen.getByText("运维手册")).toBeTruthy());
    expect((screen.getByRole("radio", { name: /pdfbox-v2/ }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText("pdfbox-v2：缺少配置：vision")).toBeTruthy();
    expect((screen.getByRole("checkbox", { name: /技术规范/ }) as HTMLInputElement).disabled).toBe(true);
    await fireEvent.update(screen.getByPlaceholderText("搜索知识库"), "运维");
    expect(screen.queryByText("产品文档")).toBeNull();
    expect(screen.getByText("运维手册")).toBeTruthy();
  });

  it("名称留空时提交 name 为 undefined", async () => {
    mocks.parsers.mockResolvedValue([{ id: "kwiki-parse-2", label: "pdfbox-v2", available: true, unavailableReason: null, requiredCapabilities: [] }]);
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }]);
    mocks.createGrayRelease.mockResolvedValue({ id: 2, status: "CREATED" });
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: {} }, global: { plugins } });
    await fireEvent.click(await screen.findByRole("checkbox", { name: /产品文档/ }));
    await fireEvent.click(screen.getByRole("button", { name: "创建灰度" }));
    await waitFor(() => expect(mocks.createGrayRelease).toHaveBeenCalledWith({ name: undefined, parserVersion: "kwiki-parse-2", indexSchema: "modern", chunkerVersion: "c", kbIds: [7] }));
  });
});
