import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import type { AssignmentView, ProviderView } from "../src/api";
import { assignmentRequests, candidatesFor, embeddingOptions, extraRows, guessTags, modelRequest, emptyModelForm, parseExtraValue, providerForm, providerRequest, rowsToExtra } from "../src/modelConfig";

const mocks = vi.hoisted(() => ({ modelProviders: vi.fn(), modelAssignments: vi.fn(), modelConfigStatus: vi.fn(), saveModelAssignments: vi.fn(), updateModelProvider: vi.fn(), createModelProvider: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
vi.mock("../src/auth", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
import ModelConfigView from "../src/views/ModelConfigView.vue";

const model = (id: number, name: string, tags: ("chat" | "vision" | "embedding")[], extra: Partial<ProviderView["models"][number]> = {}) => ({
  id, providerId: 1, modelName: name, displayName: null, tags, dimensions: tags.includes("embedding") ? 1024 : null,
  maxOutputTokens: null, supportsTools: true, supportsThinking: false, enabled: true, usedBy: [], extra: {}, ...extra,
});
const providers: ProviderView[] = [
  { id: 1, name: "bailian", protocol: "openai_compatible", baseUrl: "https://bailian/v1", apiKeyHint: "sk-****1234", requestTimeoutMs: 120000, maxRetries: 2, maxConcurrency: null, qps: null, enabled: true,
    models: [model(11, "qwen-max", ["chat"]), model(12, "qwen-no-tools", ["chat"], { supportsTools: false }), model(13, "text-embedding-v4", ["embedding"]), model(14, "old", ["chat"], { enabled: false })] },
  { id: 2, name: "offline", protocol: "openai_compatible", baseUrl: "https://off/v1", apiKeyHint: "****", requestTimeoutMs: 1000, maxRetries: 0, maxConcurrency: null, qps: null, enabled: false,
    models: [model(21, "glm-4", ["chat"])] },
];
const assignment = (purpose: string, extra: Partial<AssignmentView> = {}): AssignmentView => ({
  purpose, label: purpose, description: "", requiredTag: "chat", requiresTools: false, defaultTemperature: 0, modelId: null, temperature: null, maxTokens: null, thinking: null, effectiveModel: null, extra: {}, ...extra,
});

describe("modelConfig 纯函数", () => {
  it("候选模型按标签、启用状态与 tools 能力过滤", () => {
    expect(candidatesFor(assignment("ROUTER"), providers).flatMap(g => g.models.map(m => m.modelName))).toEqual(["qwen-max", "qwen-no-tools"]);
    expect(candidatesFor(assignment("AGENT_PLANNER", { requiresTools: true }), providers).flatMap(g => g.models.map(m => m.modelName))).toEqual(["qwen-max"]);
    expect(candidatesFor(assignment("VISION_SUMMARY", { requiredTag: "vision" }), providers)).toEqual([]);
  });

  it("embedding 选项来自启用的 embedding 模型", () => {
    expect(embeddingOptions(providers)).toEqual([{ key: "bailian/text-embedding-v4", provider: "bailian", model: "text-embedding-v4", dimensions: 1024 }]);
  });

  it("provider 表单 key 留空时请求体不带 apiKey", () => {
    const body = providerRequest(providerForm(providers[0]));
    expect("apiKey" in body).toBe(false);
    expect(providerRequest({ ...providerForm(providers[0]), apiKey: " sk-new " }).apiKey).toBe("sk-new");
  });

  it("参数为空时提交 null，非 embedding 模型不提交维度", () => {
    expect(assignmentRequests([assignment("ROUTER", { modelId: 11, temperature: "" as unknown as null })])).toEqual([{ purpose: "ROUTER", modelId: 11, temperature: null, maxTokens: null, thinking: null, extra: null }]);
    expect(modelRequest({ ...emptyModelForm(), modelName: "qwen", dimensions: 1024 }).dimensions).toBeNull();
  });

  it("按模型名猜测标签", () => {
    expect(guessTags("text-embedding-v4")).toEqual(["embedding"]);
    expect(guessTags("qwen-vl-max")).toEqual(["chat", "vision"]);
    expect(guessTags("qwen-max")).toEqual(["chat"]);
  });
});

describe("额外参数", () => {
  it("值按 JSON 解析，其余视为字符串", () => {
    expect(parseExtraValue("0.8")).toBe(0.8);
    expect(parseExtraValue(" 10 ")).toBe(10);
    expect(parseExtraValue("true")).toBe(true);
    expect(parseExtraValue('{"a":1}')).toEqual({ a: 1 });
    expect(parseExtraValue("document")).toBe("document");
    expect(() => parseExtraValue("{bad")).toThrow("合法的 JSON");
  });

  it("行与对象互相转换，空行忽略，重复与空值报错", () => {
    expect(rowsToExtra([{ key: "", value: "" }])).toBeNull();
    expect(rowsToExtra([{ key: "batchSize", value: "10" }, { key: "text_type", value: "document" }])).toEqual({ batchSize: 10, text_type: "document" });
    expect(() => rowsToExtra([{ key: "a", value: "1" }, { key: "a", value: "2" }])).toThrow("重复");
    expect(() => rowsToExtra([{ key: "a", value: " " }])).toThrow("不能为空");
    expect(extraRows({ top_p: 0.8, opts: { x: 1 }, mode: "fast" })).toEqual([{ key: "top_p", value: "0.8" }, { key: "opts", value: '{"x":1}' }, { key: "mode", value: "fast" }]);
    expect(modelRequest({ ...emptyModelForm(), modelName: "e", tags: ["embedding"], dimensions: 1024, extra: [{ key: "batchSize", value: "10" }] }).extra).toEqual({ batchSize: 10 });
  });
});

describe("模型配置页", () => {
  beforeEach(() => {
    mocks.modelProviders.mockResolvedValue(providers);
    mocks.modelAssignments.mockResolvedValue([assignment("ROUTER", { label: "意图路由", modelId: 11, effectiveModel: "bailian / qwen-max" })]);
    mocks.modelConfigStatus.mockResolvedValue({ version: 3, loadedAt: null, lastError: null, databaseVersion: 3 });
  });

  it("展示 provider、模型与掩码 key", async () => {
    render(ModelConfigView, { global: { plugins: [createPinia(), ElementPlus] } });
    await waitFor(() => expect(screen.getAllByText("bailian").length).toBeGreaterThan(0));
    expect(await screen.findByText("qwen-max")).toBeTruthy();
    expect(screen.getByText(/sk-\*\*\*\*1234/)).toBeTruthy();
    expect(screen.getByText("生效版本 3")).toBeTruthy();
  });

  it("保存用途分配后提示生效版本", async () => {
    mocks.saveModelAssignments.mockResolvedValue({ version: 4, assignments: [assignment("ROUTER", { modelId: 11, effectiveModel: "bailian / qwen-max" })] });
    render(ModelConfigView, { global: { plugins: [createPinia(), ElementPlus] } });
    await fireEvent.click(await screen.findByRole("tab", { name: "用途分配" }));
    await fireEvent.click(await screen.findByRole("button", { name: "保存分配" }));
    await waitFor(() => expect(mocks.saveModelAssignments).toHaveBeenCalledWith([{ purpose: "ROUTER", modelId: 11, temperature: null, maxTokens: null, thinking: null, extra: null }]));
    await waitFor(() => expect(screen.getByText("已生效（版本 4）")).toBeTruthy());
  });

  it("用途额外参数在弹窗中编辑，显示继承自模型的参数，并随保存分配提交", async () => {
    const withExtra = providers.map(p => p.id === 1 ? { ...p, models: p.models.map(m => m.id === 11 ? { ...m, extra: { top_p: 0.5 } } : m) } : p);
    mocks.modelProviders.mockResolvedValue(withExtra);
    mocks.saveModelAssignments.mockResolvedValue({ version: 5, assignments: [] });
    render(ModelConfigView, { global: { plugins: [createPinia(), ElementPlus] } });
    await fireEvent.click(await screen.findByRole("tab", { name: "用途分配" }));
    await fireEvent.click(await screen.findByRole("button", { name: "编辑意图路由额外参数" }));
    expect(await screen.findByText("top_p = 0.5")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "添加参数" }));
    await fireEvent.update(screen.getByLabelText("参数名 1"), "top_p");
    await fireEvent.update(screen.getByLabelText("参数值 1"), "0.9");
    await fireEvent.click(screen.getByRole("button", { name: "确认" }));
    await fireEvent.click(screen.getByRole("button", { name: "保存分配" }));
    await waitFor(() => expect(mocks.saveModelAssignments).toHaveBeenCalledWith([expect.objectContaining({ purpose: "ROUTER", extra: { top_p: 0.9 } })]));
  });
});
