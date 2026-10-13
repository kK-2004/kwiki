<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, ApiError, FORCE_RESET_CONFIRMATION, type Audit, type Config, type CommunityVersion, type MultimodalReadiness, type ProviderView, type Run, type PluginCatalog, type Version } from "../api";
import { embeddingOptions } from "../modelConfig";
import { statusLabel, statusType } from "../status";
import { auditActionLabel, auditOutcomeLabel, auditResultLabel, runKindLabel, runStateLabel, validationText } from "../labels";
import { useAuth } from "../auth";
import { pluginSelectionError } from "../pluginSelection";
import CurrentIndexCards from "../components/CurrentIndexCards.vue";

const auth = useAuth();
const plugins = ref<PluginCatalog | null>(null);
/** 模型目录中的 provider；索引版本的 embedding 只能从带 embedding 标签的模型中选择。 */
const modelProviders = ref<ProviderView[]>([]);
const embeddings = computed(() => embeddingOptions(modelProviders.value));
const versions = ref<Version[]>([]);
const runs = ref<Run[]>([]);
const audits = ref<Audit[]>([]);
const stats = ref<Record<string, unknown>[]>([]);
const alias = ref<string[]>([]);
const multimodalReadiness = ref<MultimodalReadiness | null>(null);
const indexKind = ref<"CHUNK" | "COMMUNITY">("CHUNK");
/** COMMUNITY 数据属于图构建功能；未启用时该标签页展示引导而不是报错。 */
const communityVersions = ref<CommunityVersion[]>([]);
const communityState = ref<"idle" | "loading" | "ready" | "disabled">("idle");
const loading = ref(false);
const selectedRun = ref<number | null>(Number(localStorage.getItem("kwiki_admin_run")) || null);
let timer: number | undefined;
let pollDelay = 2000;

const activeRuns = computed(() => runs.value.filter(r => ["RUNNING", "PAUSED"].includes(r.state)));
const currentRun = computed(() => runs.value.find(r => r.runId === selectedRun.value) || activeRuns.value[0]);
const totalPending = computed(() => stats.value.reduce((n, row) => n + Number(row.pending || 0) + Number(row.retrying || 0), 0));

async function load(silent = false) {
  if (!silent) loading.value = true;
  try {
    const [v, r, a, au, st, mm, catalog] = await Promise.all([
      api.versions(), api.runs(), api.alias(), api.audits(), api.stats(), api.multimodalReadiness(), api.plugins(),
    ]);
    plugins.value = catalog;
    versions.value = v; runs.value = r; alias.value = a.targets;
    multimodalReadiness.value = mm;
    audits.value = au; stats.value = st;
    if (currentRun.value) {
      selectedRun.value = currentRun.value.runId;
      localStorage.setItem("kwiki_admin_run", String(currentRun.value.runId));
    }
    pollDelay = activeRuns.value.length ? 2000 : 10000;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      auth.logout();
      location.assign("/admin/login");
    } else if (!silent) ElMessage.error("管理数据加载失败");
    pollDelay = Math.min(pollDelay * 2, 30000);
  } finally {
    loading.value = false;
    schedule();
  }
}

async function loadCommunity() {
  if (communityState.value === "loading") return;
  communityState.value = "loading";
  try {
    communityVersions.value = await api.communityVersions();
    communityState.value = "ready";
  } catch {
    communityVersions.value = [];
    communityState.value = "disabled";
  }
}

watch(indexKind, kind => {
  if (kind === "COMMUNITY" && communityState.value !== "ready") loadCommunity();
});

function schedule() {
  clearTimeout(timer);
  timer = window.setTimeout(() => load(true), pollDelay);
}
onMounted(() => load());
onBeforeUnmount(() => clearTimeout(timer));

const dialog = reactive({ config: false, editing: null as Version | null, delete: false, target: null as Version | null, confirmation: "", select: false, sourceDest: "" });
const form = reactive<Config>({ parserVersion: "", chunkerVersion: "", embeddingProvider: "default", embeddingModel: "", embeddingDimensions: 1024, indexSchema: "" });

function edit(target?: Version) {
  dialog.editing = target || null;
  const base = target?.configuration || versions.value.find(v => v.selected && !v.kbScoped)?.configuration;
  Object.assign(form, base || { parserVersion: "kwiki-parse-1", chunkerVersion: "kwiki-chunk-1", embeddingProvider: "default", embeddingModel: "", embeddingDimensions: 1024, indexSchema: "" });
  // 新建字段格式采用目录默认值，编辑保留原值。
  if (!target) form.indexSchema = plugins.value?.defaults.indexSchema || "";
  void loadEmbeddings();
  dialog.config = true;
}

const embeddingKey = computed({
  get: () => form.embeddingProvider && form.embeddingModel ? `${form.embeddingProvider}/${form.embeddingModel}` : "",
  set: (key: string) => {
    const option = embeddings.value.find(item => item.key === key);
    if (!option) return;
    form.embeddingProvider = option.provider;
    form.embeddingModel = option.model;
    form.embeddingDimensions = option.dimensions;
  },
});

async function loadEmbeddings() {
  try {
    modelProviders.value = await api.modelProviders();
  } catch {
    modelProviders.value = [];
  }
}

const selectionError = computed(() => pluginSelectionError(plugins.value, form.parserVersion, form.chunkerVersion, form.indexSchema));

async function save() {
  if (selectionError.value) return;
  try {
    if (dialog.editing) await api.command(`/versions/${dialog.editing.versionNumber}`, "PUT", form);
    else await api.command("/versions", "POST", form);
    dialog.config = false;
    ElMessage.success(dialog.editing ? "配置已保存，版本变为待迁移" : "新版本已创建，版本号由服务端分配");
    await load();
  } catch (error) {
    showError(error);
  }
}

const reset = reactive({ open: false, confirmation: "", running: false });

function openReset() {
  reset.confirmation = "";
  reset.open = true;
}

async function forceReset() {
  if (reset.confirmation.trim() !== FORCE_RESET_CONFIRMATION || reset.running) return;
  reset.running = true;
  try {
    const result = await api.forceReset(reset.confirmation.trim());
    reset.open = false;
    const rows = Object.values(result.clearedRows).reduce((sum, n) => sum + n, 0);
    const summary = `已删除 ${result.deletedIndices.length} 个 ES 索引、清空 ${rows} 条记录，并重建 ${result.physicalName}`;
    if (result.migrationError) ElMessage.warning(`${summary}；但存量迁移未能自动发起（${result.migrationError}），请在 v1 上手动开始迁移`);
    else ElMessage.success(`${summary}，存量迁移已开始（任务 #${result.migrationRunId}）`);
    await load();
  } catch (error) {
    showError(error);
  } finally {
    reset.running = false;
  }
}

async function act(target: Version, action: string) {
  try {
    if (action === "migrate") {
      const result = await api.command<{ accepted: boolean; runId?: number; code: string }>(`/versions/${target.versionNumber}/migrate`);
      if (!result.accepted) throw new ApiError(409, `版本忙碌，当前 runId=${result.runId ?? "未知"}`);
      if (result.runId) {
        selectedRun.value = result.runId;
        localStorage.setItem("kwiki_admin_run", String(result.runId));
      }
    } else if (action === "validate") {
      await api.command(`/versions/${target.versionNumber}/validate`);
    }
    ElMessage.success("操作已提交");
    pollDelay = 1000;
    await load();
  } catch (error) {
    showError(error);
  }
}

async function toggleWrite(target: Version, enabled: boolean) {
  try {
    await ElMessageBox.confirm(
      enabled
        ? "开启后将清空该版本的物理索引并开始双写，之后需执行「开始存量迁移」补入历史数据。"
        : "关闭后该版本不再接收新内容；重新开启需清空并重新全量迁移。",
      enabled ? "开启写入" : "关闭写入", { type: "warning" });
    await api.command(`/versions/${target.versionNumber}/write`, "PUT", { enabled });
    ElMessage.success(enabled ? "写入已开启" : "写入已关闭");
    pollDelay = 1000;
    await load();
  } catch (error) {
    if (error !== "cancel") showError(error);
  }
}

const writeToggleHint = (row: Version) =>
  row.kbScoped ? "灰度版本的写入由灰度发布管理"
    : row.selected ? "已发布版本不能关闭写入"
    : row.displayStatus === "MIGRATING" ? "迁移进行中"
    : "";

function openSelect(target: Version) {
  dialog.target = target; dialog.sourceDest = ""; dialog.select = true;
}
async function select() {
  if (!dialog.target) return;
  const expected = `${alias.value[0] || "unknown"}->${dialog.target.physicalName}`;
  if (dialog.sourceDest !== expected) {
    ElMessage.error(`请输入 ${expected}`);
    return;
  }
  try {
    await api.command(`/versions/${dialog.target.versionNumber}/select`);
    dialog.select = false;
    ElMessage.success("别名已原子切换");
    await load();
  } catch (error) {
    showError(error);
  }
}

function openDelete(target: Version) {
  dialog.target = target; dialog.confirmation = ""; dialog.delete = true;
}
async function remove() {
  if (!dialog.target || dialog.confirmation !== dialog.target.physicalName) return;
  try {
    await api.deleteVersion(dialog.target.versionNumber, dialog.confirmation);
    dialog.delete = false;
    ElMessage.success("物理索引已删除，版本元数据保留为 tombstone");
    await load();
  } catch (error) {
    showError(error);
  }
}

async function control(action: string) {
  if (!currentRun.value) return;
  try {
    await api.command(`/runs/${currentRun.value.runId}/${action}`);
    ElMessage.success("任务控制请求已提交");
    await load();
  } catch (error) {
    showError(error);
  }
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiError ? (error.status === 409 ? `状态冲突：${error.code}` : error.code) : "操作失败");
}

function percent(range: { minId: number; maxId: number; lastSeenId: number }) {
  const total = Math.max(1, range.maxId - range.minId + 1);
  return Math.max(0, Math.min(100, Math.round((range.lastSeenId - range.minId + 1) * 100 / total)));
}

const displayLabel = (value: string) => statusLabel[value as Version["displayStatus"]] || value;
</script>
<template>
  <div>
    <main class="page" v-loading="loading">
      <section class="hero">
        <div>
          <h1>索引版本</h1>
          <p class="muted">
            CHUNK 索引是主检索链路的正文章块向量索引；这里负责它的创建、写入开关、存量迁移与热切换。
            COMMUNITY 索引是知识图谱（GraphRAG）的社区摘要索引，数据由「知识图谱任务」页产出。
          </p>
        </div>
        <el-radio-group v-model="indexKind">
          <el-radio-button value="CHUNK">CHUNK 索引</el-radio-button>
          <el-radio-button value="COMMUNITY">COMMUNITY 索引</el-radio-button>
        </el-radio-group>
      </section>
      <CurrentIndexCards v-if="indexKind === 'CHUNK'" :versions="versions" :alias-targets="alias" @select="openSelect" />

      <template v-if="indexKind === 'COMMUNITY'">
        <section class="cards" v-if="communityState === 'ready'">
          <div class="metric">
            <span class="metric-label">社区版本数</span>
            <strong>{{ communityVersions.length }}</strong>
          </div>
          <div class="metric">
            <span class="metric-label">已绑定批次</span>
            <strong>{{ communityVersions.filter(v => v.batchId !== null).length }}</strong>
          </div>
        </section>
        <el-alert
          v-if="communityState === 'disabled'"
          type="info" :closable="false" show-icon
          title="图构建功能未开启，因此还没有 COMMUNITY 索引"
          description="需要先在配置中心开启 kwiki.graph.enabled 并在「知识图谱任务」页完成一次图构建批次；开启后此页会自动展示社区索引。"/>
        <el-table v-if="communityState === 'ready'" :data="communityVersions" row-key="versionNumber">
          <el-table-column label="COMMUNITY 版本" width="170">
            <template #default="{ row }">
              <strong>v{{ row.versionNumber }}</strong>
              <div class="muted">mapping v{{ row.mappingSchemaVersion }} · 配置修订 {{ row.configRevision }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><el-tag>{{ row.state }}</el-tag></template>
          </el-table-column>
          <el-table-column label="绑定批次" width="110" prop="batchId">
            <template #default="{ row }">{{ row.batchId ?? "-" }}</template>
          </el-table-column>
          <el-table-column label="各知识库物理索引" min-width="520">
            <template #default="{ row }">
              <el-table :data="row.physicalIndexes" size="small">
                <el-table-column prop="kbId" label="知识库" width="90"/>
                <el-table-column prop="communityPhysicalIndex" label="社区物理索引"/>
                <el-table-column prop="graphVersion" label="graphVersion" width="120"/>
                <el-table-column prop="state" label="构建状态" width="140"/>
              </el-table>
              <el-empty v-if="!row.physicalIndexes.length" description="该版本尚无知识库子任务"/>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" prop="createdAt" width="180"/>
        </el-table>
        <p class="muted" v-if="communityState === 'ready'">社区版本的“当前”由各知识库发布配对派生；构建与发布操作见「知识图谱任务」页面。</p>
      </template>

      <template v-else>
        <el-alert
          type="info" :closable="false" show-icon
          title="解析器版本说明"
          description="kwiki-parse-1 使用现有文本解析；kwiki-parse-2 额外提取 PDF 内嵌图片及已发布 Markdown 中的图片，生成检索摘要并保留图片资源标识。PDF 导入页仍可编辑文本，初始修订的图片语义按页面权限进入索引，原图可在源文件预览查看；扫描版 PDF 不支持 OCR。切换的是已开启写入、完成存量迁移且通过校验的索引版本；使用显式清单的部署还需登记 v2。"/>
        <el-alert
          v-if="multimodalReadiness && !multimodalReadiness.ready"
          type="warning" :closable="false" show-icon
          title="暂不能切换到 kwiki-parse-2"
          :description="`缺少配置：${multimodalReadiness.missingConfiguration.join('、')}。配置完成并重启服务后，再开启写入、存量迁移并校验 v2 索引。`"/>
        <section class="hero">
          <div style="display:flex;gap:12px">
            <el-button type="primary" :disabled="!plugins" @click="edit()">创建版本</el-button>
            <span class="hint self-center">版本号由服务端自动分配；新版本只有完成构建并通过校验后才能切换上线。</span>
          </div>
          <el-button type="danger" plain @click="openReset">强制重置</el-button>
        </section>
        <section class="cards">
          <div class="metric">
            <span class="metric-label">可写版本数</span>
            <strong>{{ versions.filter(v => v.writeEnabled).length }}</strong>
            <div class="hint">正在接收新内容写入的版本</div>
          </div>
          <div class="metric">
            <span class="metric-label">进行中任务</span>
            <strong>{{ activeRuns.length }}</strong>
            <div class="hint">正在迁移的 run</div>
          </div>
          <div class="metric">
            <span class="metric-label">双写待处理</span>
            <strong>{{ totalPending }}</strong>
            <div class="hint">新旧版本之间尚未同步的写入条数</div>
          </div>
        </section>
        <el-tabs>
          <el-tab-pane label="版本与操作">
            <p class="tab-desc">每个版本是一个独立物理索引；「已发布」表示它正被线上读别名指向。</p>
            <el-table :data="versions" row-key="versionNumber">
              <el-table-column label="版本" width="150">
                <template #default="{ row }">
                  <strong>v{{ row.versionNumber }}</strong>
                  <div class="muted">{{ row.physicalName }}</div>
                  <div class="muted">{{ row.configuration.parserVersion }}</div>
                  <div class="muted">{{ row.indexSchemaLabel }}（{{ row.configuration.indexSchema }}）</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.displayStatus)">{{ displayLabel(row.displayStatus) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="写入" width="110">
                <template #default="{ row }">
                  <el-tooltip :content="writeToggleHint(row)" :disabled="!writeToggleHint(row)">
                    <el-switch
                      :model-value="row.writeEnabled"
                      :disabled="!row.allowedActions.writeToggle"
                      @change="(value: string | number | boolean) => toggleWrite(row, Boolean(value))"/>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="配置/构建修订" width="140">
                <template #default="{ row }">
                  {{ row.configRevision }} / {{ row.builtConfigRevision ?? "-" }}
                  <div class="muted">{{ row.configuration.embeddingModel }} · {{ row.configuration.embeddingDimensions }}D</div>
                </template>
              </el-table-column>
              <el-table-column label="最近校验" min-width="190">
                <template #default="{ row }">
                  <!-- 引用未注册插件的版本：历史校验结论已不成立，改为显示不受支持的原因 -->
                  <template v-if="row.unsupportedReason">
                    <span class="validation-fail">不受支持：{{ row.unsupportedReason }}</span>
                    <div class="muted">只能关闭写入后清理</div>
                  </template>
                  <template v-else>
                  <el-tooltip :content="row.validationSummary" :disabled="!row.validationSummary" placement="top">
                    <span :class="{ 'validation-pass': validationText(row.validationSummary).passed === true, 'validation-fail': validationText(row.validationSummary).passed === false }">
                      {{ validationText(row.validationSummary).text }}
                    </span>
                  </el-tooltip>
                  <div v-if="row.lastValidationAt" class="muted">{{ row.lastValidationAt.replace("T", " ").slice(0, 19) }}</div>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="可执行操作" min-width="410">
                <template #default="{ row }">
                  <div class="actions">
                    <el-button v-if="row.allowedActions.edit" @click="edit(row)">编辑</el-button>
                    <el-button v-if="row.allowedActions.migrate" type="primary" @click="act(row, 'migrate')">开始存量迁移</el-button>
                    <el-tooltip v-else-if="row.migrateBlockedReason" :content="row.migrateBlockedReason">
                      <span><el-button type="primary" disabled>开始存量迁移</el-button></span>
                    </el-tooltip>
                    <el-button v-if="row.allowedActions.validate" @click="act(row, 'validate')">校验</el-button>
                    <el-button v-if="row.allowedActions.select" type="success" @click="openSelect(row)">选择版本</el-button>
                    <el-button v-if="row.allowedActions.delete" type="danger" @click="openDelete(row)">清理</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="迁移记录">
            <p class="tab-desc">存量迁移 = 写入开启后，按版本解析器从原始文档重建双写起点之前的历史内容；之后的变更由双写负责。</p>
            <div v-if="currentRun">
              <el-descriptions :column="4" border>
                <el-descriptions-item label="runId">{{ currentRun.runId }}</el-descriptions-item>
                <el-descriptions-item label="版本">v{{ currentRun.versionNumber }}</el-descriptions-item>
                <el-descriptions-item label="状态">{{ runKindLabel(currentRun.kind) }} · {{ runStateLabel(currentRun.state) }}</el-descriptions-item>
                <el-descriptions-item label="双写起点">{{ currentRun.buildStartEventId }}</el-descriptions-item>
              </el-descriptions>
              <!-- 只有进行中的任务可控制：进行中可暂停，已暂停可恢复（从断点继续扫描），两者都可取消 -->
              <p v-if="currentRun.state === 'RUNNING' || currentRun.state === 'PAUSED'">
                <el-button v-if="currentRun.state === 'RUNNING'" @click="control('pause')">暂停</el-button>
                <el-button v-if="currentRun.state === 'PAUSED'" @click="control('resume')">继续</el-button>
                <el-button type="danger" @click="control('cancel')">取消</el-button>
              </p>
              <el-table :data="currentRun.ranges">
                <el-table-column prop="resourceType" label="资源"/>
                <el-table-column label="基线范围" class-name="range">
                  <template #default="{ row }">
                    <el-progress :percentage="percent(row)"/>{{ row.lastSeenId }} / {{ row.maxId }}
                  </template>
                </el-table-column>
                <el-table-column label="统计">
                  <template #default="{ row }">扫描 {{ row.scanned }} · 成功 {{ row.succeeded }} · 跳过 {{ row.skipped }} · 失败 {{ row.failed }}</template>
                </el-table-column>
              </el-table>
            </div>
            <el-empty v-else description="没有活动或最近关注的任务"/>
          </el-tab-pane>
          <el-tab-pane label="双写统计">
            <p class="tab-desc">各版本实时双写任务的执行情况；「待处理」归零且迁移完成后才能通过校验。</p>
            <el-table :data="stats">
              <el-table-column prop="target_version" label="版本"/>
              <el-table-column prop="total" label="总数"/>
              <el-table-column prop="succeeded" label="成功"/>
              <el-table-column prop="pending" label="待执行"/>
              <el-table-column prop="retrying" label="重试"/>
              <el-table-column prop="failed" label="失败"/>
              <el-table-column prop="lag_seconds" label="延迟(s)"/>
              <el-table-column prop="throughput_per_second" label="吞吐(/s)"/>
              <el-table-column prop="elapsed_seconds" label="耗时(s)"/>
              <el-table-column prop="eta_seconds" label="ETA(s)"/>
              <el-table-column prop="embedding_calls" label="Embedding 调用"/>
              <el-table-column prop="last_transition_at" label="最近变化"/>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="审计">
            <p class="tab-desc">所有管理操作（创建、写入开关、存量迁移、校验、切换、清理等）的留痕；校验结论与未通过原因也记录在此。</p>
            <el-table :data="audits">
              <el-table-column label="时间" width="180">
                <template #default="{ row }">{{ row.createdAt.replace("T", " ").slice(0, 19) }}</template>
              </el-table-column>
              <el-table-column prop="operator" label="操作者" width="120"/>
              <el-table-column label="动作" width="150">
                <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
              </el-table-column>
              <el-table-column label="版本" width="80">
                <template #default="{ row }">{{ row.targetVersion != null ? `v${row.targetVersion}` : "—" }}</template>
              </el-table-column>
              <el-table-column label="执行" width="100">
                <template #default="{ row }">{{ auditOutcomeLabel(row.outcome) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="130">
                <template #default="{ row }">{{ auditResultLabel(row.resultState) }}</template>
              </el-table-column>
              <el-table-column label="说明" min-width="260">
                <template #default="{ row }">{{ row.errorSummary ? (row.resultState === "FAIL" ? validationText(row.errorSummary).text : row.errorSummary) : "" }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </main>

    <el-dialog v-model="dialog.config" :title="dialog.editing ? '编辑 v' + dialog.editing.versionNumber : '创建新版本（自动编号）'" width="620">
      <el-alert title="保存后为待迁移；不会自动创建索引或切换别名" type="info" class="dialog-alert"/>
      <el-form label-width="150px">
        <el-form-item label="解析器">
          <el-select v-model="form.parserVersion" aria-label="解析器">
            <el-option v-for="option in plugins?.parsers" :key="option.id" :value="option.id" :label="`${option.label}（${option.id}）`" :disabled="!option.available">
              {{ option.label }}（{{ option.id }}）<span v-if="!option.available"> · {{ option.unavailableReason }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="分块器">
          <el-select v-model="form.chunkerVersion" aria-label="分块器">
            <el-option v-for="option in plugins?.chunkers" :key="option.id" :value="option.id" :label="`${option.label}（${option.id}）`" :disabled="!option.available">
              {{ option.label }}（{{ option.id }}）<span v-if="!option.available"> · {{ option.unavailableReason }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-select v-model="embeddingKey" aria-label="Embedding 模型" placeholder="从模型目录选择">
            <el-option v-for="option in embeddings" :key="option.key" :value="option.key" :label="`${option.provider} / ${option.model}（${option.dimensions} 维）`" />
          </el-select>
          <div class="hint">来自「模型配置」中带向量标签的模型；更换模型需要全量重建索引。向量维度：{{ form.embeddingDimensions }}</div>
        </el-form-item>
        <el-form-item label="字段格式">
          <el-select v-model="form.indexSchema" aria-label="字段格式">
            <el-option v-for="option in plugins?.schemas" :key="option.id" :value="option.id" :label="`${option.label}（${option.id}）`" :disabled="!option.available">
              {{ option.label }}（{{ option.id }}）<span v-if="!option.available"> · {{ option.unavailableReason }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-alert v-if="selectionError" :title="selectionError" type="warning" :closable="false" />
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialog.config = false">取消</el-button>
          <el-button type="primary" :disabled="Boolean(selectionError)" @click="save">保存</el-button>
        </div>
      </template>
    </el-dialog>
    <el-dialog v-model="reset.open" title="强制重置全部索引" width="600">
      <el-alert type="error" :closable="false" title="此操作不可撤销，执行期间及迁移完成前检索结果为空或不完整" class="dialog-alert"/>
      <p>将执行：</p>
      <ul style="margin:0 0 8px;padding-left:20px;line-height:1.8">
        <li>删除全部 CHUNK 索引（kwiki-chunks-v*）、读别名，以及全部 COMMUNITY 社区索引</li>
        <li>清空索引版本、重建任务、校验报告、索引审计、变更事件与索引任务记录</li>
        <li>清空全部灰度发布，以及知识图谱的构建、快照、发布记录和图数据</li>
        <li>按默认配置重建 v1 并挂上读别名，随后自动对全部 Wiki 内容发起存量迁移</li>
      </ul>
      <p class="muted">不会改动解析器、分块器、字段格式等插件，模型配置，以及 Wiki 内容和附件。存在运行中的重建或图谱构建任务时会被拒绝。</p>
      <p>请输入 <strong>{{ FORCE_RESET_CONFIRMATION }}</strong> 确认</p>
      <el-input v-model="reset.confirmation" aria-label="强制重置确认词"/>
      <template #footer>
        <el-button @click="reset.open = false">取消</el-button>
        <el-button type="danger" :loading="reset.running" :disabled="reset.confirmation.trim() !== FORCE_RESET_CONFIRMATION" @click="forceReset">强制重置</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="dialog.select" title="原子切换读别名" width="560">
      <p>最新校验：{{ validationText(dialog.target?.validationSummary).text }}。切换前服务端仍会重新校验状态与 ES 别名事实。</p>
      <p>请输入 <strong>{{ alias[0] || "unknown" }}-&gt;{{ dialog.target?.physicalName }}</strong></p>
      <el-input v-model="dialog.sourceDest"/>
      <template #footer>
        <el-button @click="dialog.select = false">取消</el-button>
        <el-button type="success" @click="select">确认切换</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="dialog.delete" title="物理删除索引" width="560">
      <p class="danger-copy">此操作删除 ES 数据且不可由系统自动恢复；版本号仍保留，不会复用。别名目标、写入版本和活动任务目标会被服务端拒绝。</p>
      <p>请输入完整索引名 <strong>{{ dialog.target?.physicalName }}</strong></p>
      <el-input v-model="dialog.confirmation"/>
      <template #footer>
        <el-button @click="dialog.delete = false">取消</el-button>
        <el-button type="danger" :disabled="dialog.confirmation !== dialog.target?.physicalName" @click="remove">永久删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>
