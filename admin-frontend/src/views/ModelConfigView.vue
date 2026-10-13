<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, ApiError, type AssignmentView, type ModelConfigStatus, type ModelView, type ProviderView } from "../api";
import { useAuth } from "../auth";
import {
  TAG_LABELS, assignmentRequests, candidatesFor, emptyModelForm, emptyProviderForm, extraRows, extraSummary,
  guessTags, modelForm, modelRequest, providerForm, providerRequest, rowsToExtra, type ExtraRow, type ModelForm,
  type ProviderForm,
} from "../modelConfig";
import ExtraParamsEditor from "../components/ExtraParamsEditor.vue";

const auth = useAuth();
const tab = ref<"providers" | "assignments">("providers");
const loading = ref(false);
const providers = ref<ProviderView[]>([]);
const assignments = ref<AssignmentView[]>([]);
const status = ref<ModelConfigStatus | null>(null);
const selectedId = ref<number | null>(null);
const selected = computed(() => providers.value.find(p => p.id === selectedId.value) || null);
const savingAssignments = ref(false);

const providerDialog = reactive({ open: false, editing: null as ProviderView | null, form: emptyProviderForm() as ProviderForm, testing: false, result: "" });
const modelDialog = reactive({ open: false, editing: null as ModelView | null, form: emptyModelForm() as ModelForm });
/** 用途分配的额外参数编辑：确认后写回行数据，随「保存分配」一起提交。 */
const extraDialog = reactive({ open: false, row: null as AssignmentView | null, rows: [] as ExtraRow[], inherited: [] as ExtraRow[] });

function modelById(id: number | null) {
  if (id == null) return null;
  for (const provider of providers.value) {
    const found = provider.models.find(model => model.id === id);
    if (found) return found;
  }
  return null;
}

function openExtra(row: AssignmentView) {
  extraDialog.row = row;
  extraDialog.rows = extraRows(row.extra);
  extraDialog.inherited = extraRows(modelById(row.modelId)?.extra);
  extraDialog.open = true;
}

function confirmExtra() {
  if (!extraDialog.row) return;
  try {
    extraDialog.row.extra = rowsToExtra(extraDialog.rows) || {};
    extraDialog.open = false;
  } catch (error) {
    failure(error, "额外参数无效");
  }
}

const discoverDialog = reactive({ open: false, loading: false, names: [] as string[], picked: [] as string[], dimensions: {} as Record<string, number | null> });

function failure(error: unknown, fallback: string) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    auth.logout();
    location.assign("/admin/login");
    return;
  }
  if (error instanceof ApiError) ElMessage.error(error.code !== "request_failed" ? error.code : fallback);
  else ElMessage.error(error instanceof Error && error.message ? error.message : fallback);
}

async function load() {
  loading.value = true;
  try {
    const [p, a, s] = await Promise.all([api.modelProviders(), api.modelAssignments(), api.modelConfigStatus()]);
    providers.value = p;
    assignments.value = a;
    status.value = s;
    if (!selected.value) selectedId.value = p[0]?.id ?? null;
  } catch (error) {
    failure(error, "模型配置加载失败");
  } finally {
    loading.value = false;
  }
}

// ---------- provider ----------

function openProvider(provider?: ProviderView) {
  providerDialog.editing = provider || null;
  providerDialog.form = provider ? providerForm(provider) : emptyProviderForm();
  providerDialog.result = "";
  providerDialog.open = true;
}

async function saveProvider() {
  try {
    const body = providerRequest(providerDialog.form);
    const saved = providerDialog.editing
      ? await api.updateModelProvider(providerDialog.editing.id, body)
      : await api.createModelProvider(body);
    providerDialog.open = false;
    selectedId.value = saved.id;
    ElMessage.success("provider 已保存并生效");
    await load();
  } catch (error) {
    failure(error, "保存失败");
  }
}

async function testProvider() {
  providerDialog.testing = true;
  try {
    const form = providerRequest(providerDialog.form);
    const result = await api.testModelProvider({ providerId: providerDialog.editing?.id ?? null, baseUrl: form.baseUrl, apiKey: form.apiKey, requestTimeoutMs: form.requestTimeoutMs });
    providerDialog.result = `${result.ok ? "✓" : "✗"} ${result.message}（${result.latencyMs} ms）`;
  } catch (error) {
    failure(error, "测试失败");
  } finally {
    providerDialog.testing = false;
  }
}

async function removeProvider(provider: ProviderView) {
  try {
    await ElMessageBox.confirm(`删除 provider「${provider.name}」及其下全部模型？`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await api.deleteModelProvider(provider.id);
    selectedId.value = null;
    ElMessage.success("已删除");
    await load();
  } catch (error) {
    failure(error, "删除失败");
  }
}

// ---------- 模型 ----------

function openModel(model?: ModelView) {
  modelDialog.editing = model || null;
  modelDialog.form = model ? modelForm(model) : emptyModelForm();
  modelDialog.open = true;
}

async function saveModel() {
  if (!selected.value) return;
  try {
    const body = modelRequest(modelDialog.form);
    if (modelDialog.editing) await api.updateModel(modelDialog.editing.id, body);
    else await api.createModels(selected.value.id, [body]);
    modelDialog.open = false;
    ElMessage.success("模型已保存并生效");
    await load();
  } catch (error) {
    failure(error, "保存失败");
  }
}

async function removeModel(model: ModelView) {
  try {
    await ElMessageBox.confirm(`删除模型「${model.modelName}」？`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await api.deleteModel(model.id);
    await load();
  } catch (error) {
    failure(error, "删除失败");
  }
}

async function testModel(model: ModelView) {
  try {
    const result = await api.testModel(model.id);
    if (result.ok) ElMessage.success(`${model.modelName}：${result.message}（${result.latencyMs} ms）`);
    else ElMessage.error(`${model.modelName}：${result.message}`);
  } catch (error) {
    failure(error, "测试失败");
  }
}

async function openDiscover() {
  if (!selected.value) return;
  discoverDialog.open = true;
  discoverDialog.loading = true;
  discoverDialog.names = [];
  discoverDialog.picked = [];
  discoverDialog.dimensions = {};
  try {
    discoverDialog.names = await api.discoverModels(selected.value.id);
  } catch (error) {
    failure(error, "拉取模型列表失败");
  } finally {
    discoverDialog.loading = false;
  }
}

async function addDiscovered() {
  if (!selected.value || !discoverDialog.picked.length) return;
  try {
    await api.createModels(selected.value.id, discoverDialog.picked.map(name => {
      const tags = guessTags(name);
      return modelRequest({ ...emptyModelForm(), modelName: name, tags, supportsTools: tags.includes("chat"), dimensions: discoverDialog.dimensions[name] ?? null });
    }));
    discoverDialog.open = false;
    ElMessage.success(`已添加 ${discoverDialog.picked.length} 个模型，标签可在列表中调整`);
    await load();
  } catch (error) {
    failure(error, "添加失败");
  }
}

const pickedEmbeddings = computed(() => discoverDialog.picked.filter(name => guessTags(name).includes("embedding")));

// ---------- 用途分配 ----------

async function saveAssignments() {
  savingAssignments.value = true;
  try {
    const result = await api.saveModelAssignments(assignmentRequests(assignments.value));
    assignments.value = result.assignments;
    status.value = await api.modelConfigStatus();
    ElMessage.success(`已生效（版本 ${result.version}）`);
  } catch (error) {
    failure(error, "保存失败");
  } finally {
    savingAssignments.value = false;
  }
}

onMounted(load);
</script>

<template>
  <main class="page" v-loading="loading">
    <section class="hero">
      <div>
        <h1>模型配置</h1>
        <p class="muted">维护模型服务提供方（provider）与其下的模型，再为 planner、意图路由、回答等各个用途分别指定模型。保存后立即生效，无需重启。</p>
      </div>
      <div v-if="status" class="status" :class="{ error: status.lastError }">
        <span>生效版本 {{ status.version }}</span>
        <span v-if="status.lastError">最近一次加载失败：{{ status.lastError }}</span>
      </div>
    </section>

    <el-tabs v-model="tab">
      <el-tab-pane label="Provider 与模型" name="providers">
        <div class="layout">
          <aside class="providers">
            <el-button type="primary" class="full" @click="openProvider()">新增 provider</el-button>
            <el-empty v-if="!providers.length" description="尚未配置 provider" />
            <button v-for="provider in providers" :key="provider.id" type="button" class="provider" :class="{ active: provider.id === selectedId, disabled: !provider.enabled }" @click="selectedId = provider.id">
              <strong>{{ provider.name }}</strong>
              <small>{{ provider.baseUrl }}</small>
              <small>{{ provider.models.length }} 个模型 · {{ provider.apiKeyHint }}<template v-if="!provider.enabled"> · 已停用</template></small>
            </button>
          </aside>
          <section v-if="selected" class="detail">
            <header class="detail-head">
              <div>
                <h2>{{ selected.name }}</h2>
                <p class="muted">{{ selected.baseUrl }} · 超时 {{ selected.requestTimeoutMs }} ms · 重试 {{ selected.maxRetries }}<template v-if="selected.maxConcurrency"> · 并发 {{ selected.maxConcurrency }}</template><template v-if="selected.qps"> · QPS {{ selected.qps }}</template></p>
              </div>
              <div class="actions">
                <el-button @click="openProvider(selected)">编辑</el-button>
                <el-button @click="openDiscover">从 provider 拉取模型</el-button>
                <el-button type="primary" @click="openModel()">添加模型</el-button>
                <el-button type="danger" text @click="removeProvider(selected)">删除</el-button>
              </div>
            </header>
            <el-table :data="selected.models" empty-text="尚未登记模型">
              <el-table-column label="模型" min-width="200">
                <template #default="{ row }"><strong>{{ row.modelName }}</strong><div v-if="row.displayName" class="muted">{{ row.displayName }}</div></template>
              </el-table-column>
              <el-table-column label="标签" min-width="150">
                <template #default="{ row }"><el-tag v-for="tag in row.tags" :key="tag" size="small" class="tag">{{ TAG_LABELS[tag as keyof typeof TAG_LABELS] }}</el-tag></template>
              </el-table-column>
              <el-table-column label="维度" width="80"><template #default="{ row }">{{ row.dimensions ?? "—" }}</template></el-table-column>
              <el-table-column label="能力" width="150">
                <template #default="{ row }"><span class="muted">{{ [row.supportsTools ? "tools" : "", row.supportsThinking ? "thinking" : ""].filter(Boolean).join(" · ") || "—" }}</span></template>
              </el-table-column>
              <el-table-column label="被引用" min-width="160">
                <template #default="{ row }"><span class="muted">{{ row.usedBy.join("、") || "—" }}</span></template>
              </el-table-column>
              <el-table-column label="额外参数" min-width="140">
                <template #default="{ row }"><span class="muted">{{ extraSummary(row.extra) || "—" }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "启用" : "停用" }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row }">
                  <el-button text size="small" @click="testModel(row)">测试</el-button>
                  <el-button text size="small" @click="openModel(row)">编辑</el-button>
                  <el-button text size="small" type="danger" @click="removeModel(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane label="用途分配" name="assignments">
        <el-table :data="assignments">
          <el-table-column label="用途" min-width="220">
            <template #default="{ row }"><strong>{{ row.label }}</strong> <span class="muted">{{ row.purpose }}</span><div class="muted">{{ row.description }}</div></template>
          </el-table-column>
          <el-table-column label="模型" min-width="260">
            <template #default="{ row }">
              <el-select v-model="row.modelId" clearable placeholder="未分配" :aria-label="`${row.label}模型`">
                <el-option-group v-for="group in candidatesFor(row, providers)" :key="group.provider.id" :label="group.provider.name">
                  <el-option v-for="model in group.models" :key="model.id" :value="model.id" :label="`${group.provider.name} / ${model.modelName}`" />
                </el-option-group>
              </el-select>
              <div v-if="row.requiresTools" class="muted">需要支持 tool calling 的模型</div>
              <div v-if="!row.effectiveModel" class="warn">当前未生效</div>
            </template>
          </el-table-column>
          <el-table-column label="temperature" width="140">
            <template #default="{ row }"><el-input-number v-model="row.temperature" :min="0" :max="2" :step="0.1" :precision="2" controls-position="right" :placeholder="String(row.defaultTemperature)" :value-on-clear="null" /></template>
          </el-table-column>
          <el-table-column label="maxTokens" width="150">
            <template #default="{ row }"><el-input-number v-model="row.maxTokens" :min="1" :step="256" controls-position="right" placeholder="模型默认" :value-on-clear="null" /></template>
          </el-table-column>
          <el-table-column label="额外参数" width="200">
            <template #default="{ row }">
              <span class="muted extra-summary">{{ extraSummary(row.extra) || "—" }}</span>
              <el-button text size="small" :aria-label="`编辑${row.label}额外参数`" @click="openExtra(row)">编辑</el-button>
            </template>
          </el-table-column>
          <el-table-column label="thinking" width="130">
            <template #default="{ row }">
              <el-select v-model="row.thinking" placeholder="provider 默认" clearable :aria-label="`${row.label} thinking`">
                <el-option :value="true" label="开启" />
                <el-option :value="false" label="关闭" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <div class="footer">
          <span class="muted">参数留空表示使用默认值；保存后立即在所有实例生效（其他实例最迟 30 秒）。</span>
          <el-button type="primary" :loading="savingAssignments" @click="saveAssignments">保存分配</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="providerDialog.open" :title="providerDialog.editing ? '编辑 provider' : '新增 provider'" width="560">
      <el-form label-width="120px">
        <el-form-item label="名称"><el-input v-model="providerDialog.form.name" aria-label="provider 名称" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="providerDialog.form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" aria-label="Base URL" /><div class="hint">OpenAI 兼容的 API 根路径，通常以 /v1 结尾（会请求 &lt;Base URL&gt;/models、/chat/completions）。</div></el-form-item>
        <el-form-item label="API key">
          <el-input v-model="providerDialog.form.apiKey" type="password" show-password :placeholder="providerDialog.editing ? `${providerDialog.editing.apiKeyHint}（留空不修改）` : ''" aria-label="API key" />
        </el-form-item>
        <el-form-item label="超时（毫秒）"><el-input-number v-model="providerDialog.form.requestTimeoutMs" :min="1000" :max="600000" :step="1000" :value-on-clear="null" /></el-form-item>
        <el-form-item label="重试次数"><el-input-number v-model="providerDialog.form.maxRetries" :min="0" :max="10" :value-on-clear="null" /></el-form-item>
        <el-form-item label="并发上限"><el-input-number v-model="providerDialog.form.maxConcurrency" :min="1" :max="256" placeholder="不限" :value-on-clear="null" /></el-form-item>
        <el-form-item label="QPS"><el-input-number v-model="providerDialog.form.qps" :min="0.1" :step="1" :precision="1" placeholder="不限" :value-on-clear="null" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="providerDialog.form.enabled" /></el-form-item>
        <p v-if="providerDialog.result" class="result">{{ providerDialog.result }}</p>
      </el-form>
      <template #footer>
        <el-button :loading="providerDialog.testing" @click="testProvider">测试连接</el-button>
        <el-button @click="providerDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelDialog.open" :title="modelDialog.editing ? '编辑模型' : '添加模型'" width="680">
      <el-form label-width="110px">
        <el-form-item label="模型名"><el-input v-model="modelDialog.form.modelName" placeholder="发给 API 的模型名，如 qwen-max" aria-label="模型名" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="modelDialog.form.displayName" /></el-form-item>
        <el-form-item label="标签">
          <el-checkbox-group v-model="modelDialog.form.tags">
            <el-checkbox v-for="(label, tag) in TAG_LABELS" :key="tag" :value="tag">{{ label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="modelDialog.form.tags.includes('embedding')" label="向量维度">
          <el-input-number v-model="modelDialog.form.dimensions" :min="64" :max="2048" :value-on-clear="null" />
          <div class="hint">必须与模型实际输出一致；被索引版本引用后不可修改。</div>
        </el-form-item>
        <el-form-item label="输出上限"><el-input-number v-model="modelDialog.form.maxOutputTokens" :min="1" placeholder="自动探测" :value-on-clear="null" /></el-form-item>
        <el-form-item label="能力">
          <el-checkbox v-model="modelDialog.form.supportsTools">原生 tool calling</el-checkbox>
          <el-checkbox v-model="modelDialog.form.supportsThinking">thinking</el-checkbox>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="modelDialog.form.enabled" /></el-form-item>
        <el-form-item label="额外参数">
          <ExtraParamsEditor v-model="modelDialog.form.extra" />
          <div v-if="modelDialog.form.tags.includes('embedding')" class="hint">embedding 模型通过 <code>batchSize</code> 控制每批条数，不填为 10。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="extraDialog.open" :title="`额外参数：${extraDialog.row?.label || ''}`" width="640">
      <ExtraParamsEditor v-model="extraDialog.rows" :inherited="extraDialog.inherited" />
      <p class="hint">用途上的同名参数覆盖模型上的；确认后点击「保存分配」才会生效。</p>
      <template #footer>
        <el-button @click="extraDialog.open = false">取消</el-button>
        <el-button type="primary" @click="confirmExtra">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="discoverDialog.open" title="从 provider 拉取模型" width="560">
      <div v-loading="discoverDialog.loading">
        <el-empty v-if="!discoverDialog.loading && !discoverDialog.names.length" description="没有未登记的模型" />
        <el-checkbox-group v-model="discoverDialog.picked" class="discover">
          <el-checkbox v-for="name in discoverDialog.names" :key="name" :value="name">{{ name }} <span class="muted">（{{ guessTags(name).map(t => TAG_LABELS[t]).join("、") }}）</span></el-checkbox>
        </el-checkbox-group>
        <div v-for="name in pickedEmbeddings" :key="name" class="dims">
          <span>{{ name }} 的维度</span>
          <el-input-number v-model="discoverDialog.dimensions[name]" :min="64" :max="2048" :value-on-clear="null" />
        </div>
      </div>
      <template #footer>
        <el-button @click="discoverDialog.open = false">取消</el-button>
        <el-button type="primary" :disabled="!discoverDialog.picked.length" @click="addDiscovered">添加所选</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.status { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--k-muted); text-align: right; }
.status.error { color: var(--el-color-danger); }
.layout { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 16px; align-items: start; }
.providers { display: flex; flex-direction: column; gap: 8px; }
.full { width: 100%; }
.provider { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; text-align: left; border: 1px solid var(--k-line); border-radius: 8px; background: var(--k-surface); color: inherit; cursor: pointer; }
.provider small { color: var(--k-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.provider.active { border-color: var(--el-color-primary); }
.provider.disabled { opacity: .6; }
.detail-head { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
.detail-head h2 { margin: 0 0 4px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.tag { margin-right: 4px; }
.warn { color: var(--el-color-warning); font-size: 12px; }
.footer { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; gap: 12px; }
.hint { font-size: 12px; color: var(--k-muted); width: 100%; }
.result { margin: 0; font-size: 13px; }
.extra-summary { display: inline-block; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.discover { display: flex; flex-direction: column; gap: 6px; }
.dims { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
</style>
