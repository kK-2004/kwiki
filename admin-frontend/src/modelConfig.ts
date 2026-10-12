import type { AssignmentRequest, AssignmentView, ModelRequest, ModelTag, ModelView, ProviderRequest, ProviderView } from "./api";

export const TAG_LABELS: Record<ModelTag, string> = { chat: "对话", vision: "图片理解", embedding: "向量" };

/** 用途下拉框的候选：只列出已启用 provider 下、已启用且带所需标签的模型；需要 tools 时再过滤一次。 */
export function candidatesFor(assignment: Pick<AssignmentView, "requiredTag" | "requiresTools">, providers: ProviderView[]) {
  return providers
    .filter(provider => provider.enabled)
    .map(provider => ({
      provider,
      models: provider.models.filter(model => model.enabled && model.tags.includes(assignment.requiredTag)
        && (!assignment.requiresTools || model.supportsTools)),
    }))
    .filter(group => group.models.length > 0);
}

/** 索引版本表单可选的 embedding 模型：值为 provider 名 + 模型名 + 维度。 */
export function embeddingOptions(providers: ProviderView[]) {
  return providers.filter(provider => provider.enabled).flatMap(provider => provider.models
    .filter(model => model.enabled && model.tags.includes("embedding") && model.dimensions)
    .map(model => ({ key: `${provider.name}/${model.modelName}`, provider: provider.name, model: model.modelName, dimensions: model.dimensions as number })));
}

/** 输入框里空字符串 / undefined 一律提交 null（表示使用代码默认值）。 */
export function blankToNull<T>(value: T | "" | undefined | null): T | null {
  return value === "" || value === undefined || value === null ? null : value;
}

export interface ProviderForm { name: string; baseUrl: string; apiKey: string; requestTimeoutMs: number | null; maxRetries: number | null; maxConcurrency: number | null; qps: number | null; enabled: boolean }

export function emptyProviderForm(): ProviderForm {
  return { name: "", baseUrl: "", apiKey: "", requestTimeoutMs: 120000, maxRetries: 2, maxConcurrency: null, qps: null, enabled: true };
}

export function providerForm(provider: ProviderView): ProviderForm {
  return { name: provider.name, baseUrl: provider.baseUrl, apiKey: "", requestTimeoutMs: provider.requestTimeoutMs, maxRetries: provider.maxRetries, maxConcurrency: provider.maxConcurrency, qps: provider.qps, enabled: provider.enabled };
}

/** key 留空表示不修改：请求体里不带 apiKey 字段。 */
export function providerRequest(form: ProviderForm): ProviderRequest {
  const body: ProviderRequest = { name: form.name.trim(), baseUrl: form.baseUrl.trim(), requestTimeoutMs: blankToNull(form.requestTimeoutMs), maxRetries: blankToNull(form.maxRetries), maxConcurrency: blankToNull(form.maxConcurrency), qps: blankToNull(form.qps), enabled: form.enabled };
  if (form.apiKey.trim()) body.apiKey = form.apiKey.trim();
  return body;
}

export interface ModelForm { modelName: string; displayName: string; tags: ModelTag[]; dimensions: number | null; maxOutputTokens: number | null; supportsTools: boolean; supportsThinking: boolean; enabled: boolean }

export function emptyModelForm(): ModelForm {
  return { modelName: "", displayName: "", tags: ["chat"], dimensions: null, maxOutputTokens: null, supportsTools: true, supportsThinking: false, enabled: true };
}

export function modelForm(model: ModelView): ModelForm {
  return { modelName: model.modelName, displayName: model.displayName || "", tags: [...model.tags], dimensions: model.dimensions, maxOutputTokens: model.maxOutputTokens, supportsTools: model.supportsTools, supportsThinking: model.supportsThinking, enabled: model.enabled };
}

/** 按模型名猜测标签：名称含 embedding 的视为向量模型，含 vl / vision 的视为图片理解模型。 */
export function guessTags(modelName: string): ModelTag[] {
  const name = modelName.toLowerCase();
  if (name.includes("embed")) return ["embedding"];
  if (/(^|[-_])vl([-_]|$)|vision/.test(name)) return ["chat", "vision"];
  return ["chat"];
}

export function modelRequest(form: ModelForm): ModelRequest {
  const embedding = form.tags.includes("embedding");
  return { modelName: form.modelName.trim(), displayName: blankToNull(form.displayName.trim()), tags: form.tags, dimensions: embedding ? blankToNull(form.dimensions) : null, maxOutputTokens: blankToNull(form.maxOutputTokens), supportsTools: form.supportsTools, supportsThinking: form.supportsThinking, enabled: form.enabled };
}

export function assignmentRequests(rows: AssignmentView[]): AssignmentRequest[] {
  return rows.map(row => ({ purpose: row.purpose, modelId: blankToNull(row.modelId), temperature: blankToNull(row.temperature), maxTokens: blankToNull(row.maxTokens), thinking: blankToNull(row.thinking) }));
}
