<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, ApiError, type Audit, type Config, type CommunityVersion, type Run, type Validation, type Version } from "../api";
import { statusLabel, statusType } from "../status";
import { useAuth } from "../auth";

const auth = useAuth();
const versions = ref<Version[]>([]);
const runs = ref<Run[]>([]);
const validations = ref<Validation[]>([]);
const audits = ref<Audit[]>([]);
const stats = ref<Record<string, unknown>[]>([]);
const alias = ref<string[]>([]);
const indexKind = ref<"CHUNK" | "COMMUNITY">("CHUNK");
/** COMMUNITY 数据属于图构建功能；未启用时该标签页展示引导而不是报错。 */
const communityVersions = ref<CommunityVersion[]>([]);
const communityState = ref<"idle" | "loading" | "ready" | "disabled">("idle");
const loading = ref(false);
const selectedRun = ref<number | null>(Number(localStorage.getItem("kwiki_admin_run")) || null);
let timer: number | undefined;
let pollDelay = 2000;

const activeRuns = computed(() => runs.value.filter(r => ["RUNNING", "PAUSED"].includes(r.state) || r.switchState === "PREPARING"));
const currentRun = computed(() => runs.value.find(r => r.runId === selectedRun.value) || activeRuns.value[0]);
const totalPending = computed(() => stats.value.reduce((n, row) => n + Number(row.pending || 0) + Number(row.retrying || 0), 0));

async function load(silent = false) {
  if (!silent) loading.value = true;
  try {
    const [v, r, a, vr, au, st] = await Promise.all([
      api.versions(), api.runs(), api.alias(), api.validations(), api.audits(), api.stats(),
    ]);
    versions.value = v; runs.value = r; alias.value = a.targets;
    validations.value = vr; audits.value = au; stats.value = st;
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
const form = reactive<Config>({ parserVersion: "", chunkerVersion: "", embeddingProvider: "default", embeddingModel: "", embeddingDimensions: 1024, mappingSchemaVersion: 1 });

function edit(target?: Version) {
  dialog.editing = target || null;
  Object.assign(form, target?.configuration || { parserVersion: "", chunkerVersion: "", embeddingProvider: "default", embeddingModel: "", embeddingDimensions: 1024, mappingSchemaVersion: 1 });
  dialog.config = true;
}

async function save() {
  try {
    if (dialog.editing) await api.command(`/versions/${dialog.editing.versionNumber}`, "PUT", form);
    else await api.command("/versions", "POST", form);
    dialog.config = false;
    ElMessage.success(dialog.editing ? "配置已保存，版本变为待重建" : "新版本已创建，版本号由服务端分配");
    await load();
  } catch (error) {
    showError(error);
  }
}

async function act(target: Version, action: string) {
  try {
    if (action === "rebuild") {
      const result = await api.command<{ accepted: boolean; runId?: number; code: string }>(`/versions/${target.versionNumber}/rebuild`);
      if (!result.accepted) throw new ApiError(409, `版本忙碌，当前 runId=${result.runId ?? "未知"}`);
      if (result.runId) {
        selectedRun.value = result.runId;
        localStorage.setItem("kwiki_admin_run", String(result.runId));
      }
    } else if (action === "disable") {
      await ElMessageBox.confirm("停用后该版本不再接收新内容写入，恢复时必须先补齐。", "停用索引版本", { type: "warning" });
      await api.command(`/versions/${target.versionNumber}/disable`);
    } else if (action === "reenable") {
      await api.command(`/versions/${target.versionNumber}/reenable`);
    } else if (action === "prepare") {
      await api.command(`/versions/${target.versionNumber}/prepare`);
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

const latestValidation = (v: number) => validations.value.find(r => r.versionNumber === v);
const displayLabel = (value: string) => statusLabel[value as Version["displayStatus"]] || value;
</script>
<template>
  <div class="shell">
    <header class="topbar">
      <strong>KWiki · 搜索索引管理</strong>
      <nav class="topnav">
        <router-link to="/knowledge-graphs">知识图谱任务</router-link>
        <span>{{ auth.user?.username }}</span>
        <el-button text style="color:white" @click="auth.logout();$router.push('/login')">退出</el-button>
      </nav>
    </header>
    <main class="page" v-loading="loading">
      <section class="hero">
        <div>
          <h1>索引版本</h1>
          <p class="muted">
            CHUNK 索引是主检索链路的正文章块向量索引；这里负责它的创建、重建、补齐与热切换。
            COMMUNITY 索引是知识图谱（GraphRAG）的社区摘要索引，数据由「知识图谱任务」页产出。
          </p>
        </div>
        <el-radio-group v-model="indexKind">
          <el-radio-button value="CHUNK">CHUNK 索引</el-radio-button>
          <el-radio-button value="COMMUNITY">COMMUNITY 索引</el-radio-button>
        </el-radio-group>
      </section>

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
        <section class="hero">
          <div style="display:flex;gap:12px">
            <el-button type="primary" @click="edit()">创建版本</el-button>
            <span class="hint self-center">版本号由服务端自动分配；新版本只有完成构建并通过校验后才能切换上线。</span>
          </div>
        </section>
        <section class="cards">
          <div class="metric">
            <span class="metric-label">当前别名</span>
            <strong class="metric-text">{{ alias[0] || "不可用" }}</strong>
            <div class="hint">线上读请求实际命中的物理索引</div>
          </div>
          <div class="metric">
            <span class="metric-label">可写版本数</span>
            <strong>{{ versions.filter(v => v.writeEnabled).length }}</strong>
            <div class="hint">正在接收新内容写入的版本</div>
          </div>
          <div class="metric">
            <span class="metric-label">进行中任务</span>
            <strong>{{ activeRuns.length }}</strong>
            <div class="hint">正在重建 / 补齐 / 切换的 run</div>
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
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.displayStatus)">{{ displayLabel(row.displayStatus) }}</el-tag>
                  <div>{{ row.writeEnabled ? "写入启用" : "写入停用" }}</div>
                </template>
              </el-table-column>
              <el-table-column label="配置/构建修订" width="140">
                <template #default="{ row }">
                  {{ row.configRevision }} / {{ row.builtConfigRevision ?? "-" }}
                  <div class="muted">{{ row.configuration.embeddingModel }} · {{ row.configuration.embeddingDimensions }}D</div>
                </template>
              </el-table-column>
              <el-table-column label="同步与健康" min-width="190">
                <template #default="{ row }">
                  {{ row.catchupStatus }} / {{ row.healthSummary || "未知" }}
                  <div class="muted">{{ row.validationSummary || "尚未校验" }}</div>
                </template>
              </el-table-column>
              <el-table-column label="可执行操作" min-width="410">
                <template #default="{ row }">
                  <div class="actions">
                    <el-button v-if="row.allowedActions.edit" @click="edit(row)">编辑</el-button>
                    <el-button v-if="row.allowedActions.rebuild" type="primary" @click="act(row, 'rebuild')">重建</el-button>
                    <el-button v-if="row.allowedActions.prepare" @click="act(row, 'prepare')">开始补齐</el-button>
                    <el-button v-if="row.allowedActions.validate" @click="act(row, 'validate')">校验</el-button>
                    <el-button v-if="row.allowedActions.select" type="success" @click="openSelect(row)">选择版本</el-button>
                    <el-button v-if="row.allowedActions.disable" type="warning" @click="act(row, 'disable')">停用</el-button>
                    <el-button v-if="row.allowedActions.reenable" @click="act(row, 'reenable')">重新启用</el-button>
                    <el-button v-if="row.allowedActions.delete" type="danger" @click="openDelete(row)">清理</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="重建与补齐">
            <p class="tab-desc">重建 = 按新配置全量回填；补齐 = 把重建期间产生的新写入追加上去。两者都完成后才能切换。</p>
            <div v-if="currentRun">
              <el-descriptions :column="4" border>
                <el-descriptions-item label="runId">{{ currentRun.runId }}</el-descriptions-item>
                <el-descriptions-item label="版本">v{{ currentRun.versionNumber }}</el-descriptions-item>
                <el-descriptions-item label="状态">{{ currentRun.state }} / {{ currentRun.switchState }}</el-descriptions-item>
                <el-descriptions-item label="事件游标">{{ currentRun.replayEventId }} / {{ currentRun.dualWriteStartEventId ?? "-" }}</el-descriptions-item>
              </el-descriptions>
              <p>
                <el-button @click="control('pause')">暂停</el-button>
                <el-button @click="control('resume')">恢复</el-button>
                <el-button type="danger" @click="control('cancel')">取消</el-button>
              </p>
              <el-table :data="currentRun.ranges">
                <el-table-column prop="resourceType" label="资源"/>
                <el-table-column label="基线范围" class-name="range">
                  <template #default="{ row }">
                    <el-progress :percentage="percent(row)"/>{{ row.lastSeenId }} / {{ row.maxId }}
                  </template>
                </el-table-column>
                <el-table-column label="补齐范围">
                  <template #default="{ row }">{{ row.tailLastSeenId }} / {{ row.tailMaxId ?? "-" }}</template>
                </el-table-column>
                <el-table-column label="统计">
                  <template #default="{ row }">扫描 {{ row.scanned }} · 成功 {{ row.succeeded }} · 跳过 {{ row.skipped }} · 失败 {{ row.failed }}</template>
                </el-table-column>
              </el-table>
            </div>
            <el-empty v-else description="没有活动或最近关注的任务"/>
          </el-tab-pane>
          <el-tab-pane label="双写统计">
            <p class="tab-desc">切换准备后新旧版本双写的实时差距；「待处理」归零才允许切换别名。</p>
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
          <el-tab-pane label="校验">
            <p class="tab-desc">切换别名前的硬门禁检查结果；只有通过校验的版本才允许上线。</p>
            <el-table :data="validations">
              <el-table-column prop="versionNumber" label="版本"/>
              <el-table-column prop="status" label="结论"/>
              <el-table-column prop="summary" label="硬门禁/诊断" min-width="500"/>
              <el-table-column prop="createdAt" label="时间"/>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="审计">
            <p class="tab-desc">所有管理操作（创建 / 重建 / 切换 / 删除等）的留痕。</p>
            <el-table :data="audits">
              <el-table-column prop="createdAt" label="时间"/>
              <el-table-column prop="operator" label="操作者"/>
              <el-table-column prop="action" label="动作"/>
              <el-table-column prop="targetVersion" label="版本"/>
              <el-table-column prop="priorState" label="原状态"/>
              <el-table-column prop="resultState" label="结果状态"/>
              <el-table-column prop="outcome" label="结果"/>
              <el-table-column prop="errorSummary" label="净化错误"/>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </main>

    <el-dialog v-model="dialog.config" :title="dialog.editing ? '编辑 v' + dialog.editing.versionNumber : '创建新版本（自动编号）'" width="620">
      <el-alert title="保存后为待重建；不会自动创建索引或切换别名" type="info" class="dialog-alert"/>
      <el-form label-width="150px">
        <el-form-item label="Parser 版本">
          <el-input v-model="form.parserVersion" placeholder="如 tika-2.9"/>
          <div class="hint">文档解析组件的代际标识；修改后该版本会变为「待重建」。</div>
        </el-form-item>
        <el-form-item label="Chunker 版本">
          <el-input v-model="form.chunkerVersion" placeholder="切分器代际"/>
          <div class="hint">内容切分组件的代际标识，决定正文如何切成 chunk。</div>
        </el-form-item>
        <el-form-item label="Embedding 档案">
          <el-input v-model="form.embeddingProvider"/>
          <div class="hint">向量服务提供商标识（如 default）。</div>
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-input v-model="form.embeddingModel"/>
          <div class="hint">向量模型名称；更换模型需要全量重建索引。</div>
        </el-form-item>
        <el-form-item label="向量维度">
          <el-input-number v-model="form.embeddingDimensions" :min="64" :max="2048"/>
          <div class="hint">必须与模型实际输出维度一致，否则写入会被 ES 拒绝。</div>
        </el-form-item>
        <el-form-item label="Mapping schema">
          <el-input-number v-model="form.mappingSchemaVersion" :min="1"/>
          <div class="hint">ES 索引 mapping 结构代际；仅结构升级时调高。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialog.config = false">取消</el-button>
          <el-button type="primary" @click="save">保存</el-button>
        </div>
      </template>
    </el-dialog>
    <el-dialog v-model="dialog.select" title="原子切换读别名" width="560">
      <p>最新校验：{{ latestValidation(dialog.target?.versionNumber || 0)?.status || "无" }}。切换前服务端仍会重新校验状态与 ES 别名事实。</p>
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
