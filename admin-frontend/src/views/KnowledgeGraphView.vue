<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  api, ApiError,
  type GraphBatch, type GraphPublication, type GraphRun, type GraphSchedule,
  type GraphServiceStatus, type GraphSnapshotRow, type Version,
} from "../api";
import { useAuth } from "../auth";

const auth = useAuth();
/** 图构建功能由 kwiki.graph.enabled 开关控制；关闭时后端不暴露任何图管理端点。 */
const feature = ref<"checking" | "available" | "disabled">("checking");
const batches = ref<GraphBatch[]>([]);
const schedule = ref<GraphSchedule[]>([]);
const publications = ref<GraphPublication[]>([]);
const snapshots = ref<GraphSnapshotRow[]>([]);
const audits = ref<Record<string, unknown>[]>([]);
const service = ref<GraphServiceStatus | null>(null);
const chunkVersions = ref<Version[]>([]);
const knowledgeBases = ref<{ id: number; name: string }[]>([]);
const runsByBatch = ref<Record<number, GraphRun[]>>({});
const selectedBatch = ref<number | null>(null);
const loading = ref(false);
let timer: number | undefined;
let pollDelay = 3000;

const selectedRuns = computed(() =>
  selectedBatch.value ? runsByBatch.value[selectedBatch.value] || [] : []);
const aliasedVersion = computed(() => chunkVersions.value.find(v => v.selected)
  || chunkVersions.value.find(v => v.writeEnabled));

const form = reactive({
  scopeKind: "KNOWLEDGE_BASE" as "KNOWLEDGE_BASE" | "ALL",
  kbId: null as number | null,
  chunkIndexVersion: 0,
  mappingSchemaVersion: 3,
  entityLinkingVersion: "v1",
});

function dualBadge(batch: GraphBatch) {
  return `CHUNK v${batch.chunkIndexVersion} / COMMUNITY v${batch.communityIndexVersion}`;
}
function communityBadge(batch: GraphBatch) {
  return batch.communityIndexVersion ? `COMMUNITY v${batch.communityIndexVersion}` : "—（不涉及）";
}

/** 先探测功能开关：未开启时后端端点为 404，此时展示引导而非报错。 */
async function probe() {
  feature.value = "checking";
  try {
    service.value = await api.graphServiceStatus();
    feature.value = "available";
    await load();
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      auth.logout();
      location.assign("/admin/login");
      return;
    }
    feature.value = "disabled";
  }
}

async function load(silent = false) {
  if (!silent) loading.value = true;
  try {
    const [b, s, p, sn, au, cv, kbList] = await Promise.all([
      api.graphBatches(), api.graphSchedule(), api.graphPublications(),
      api.graphSnapshots(), api.graphAudits(), api.versions(), api.adminKnowledgeBases(),
    ]);
    batches.value = b; schedule.value = s; publications.value = p;
    snapshots.value = sn; audits.value = au;
    chunkVersions.value = cv.filter(v => v.pipelineSupported);
    knowledgeBases.value = kbList;
    if (!form.chunkIndexVersion && aliasedVersion.value) {
      form.chunkIndexVersion = aliasedVersion.value.versionNumber;
    }
    if (!selectedBatch.value && b.length) selectedBatch.value = b[0].id;
    if (selectedBatch.value) runsByBatch.value[selectedBatch.value] = await api.graphRuns(selectedBatch.value);
    pollDelay = 3000;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      auth.logout();
      location.assign("/admin/login");
    } else if (!silent) {
      ElMessage.error("图任务数据加载失败");
    }
    pollDelay = Math.min(pollDelay * 2, 30000);
  } finally {
    loading.value = false;
    schedulePoll();
  }
}

function schedulePoll() {
  clearTimeout(timer);
  if (feature.value === "available") timer = window.setTimeout(() => load(true), pollDelay);
}

onMounted(probe);
onBeforeUnmount(() => clearTimeout(timer));

async function selectBatch(id: number) {
  selectedBatch.value = id;
  try {
    runsByBatch.value[id] = await api.graphRuns(id);
  } catch {
    ElMessage.error("子任务加载失败");
  }
}

async function submit() {
  if (form.scopeKind === "KNOWLEDGE_BASE" && !form.kbId) {
    ElMessage.error("请选择知识库");
    return;
  }
  const target = chunkVersions.value.find(v => v.versionNumber === form.chunkIndexVersion);
  if (!target) {
    ElMessage.error("请选择 Chunk 目标版本");
    return;
  }
  try {
    const result = await api.graphSubmit({
      scopeKind: form.scopeKind,
      knowledgeBaseIds: form.scopeKind === "KNOWLEDGE_BASE" ? [form.kbId] : [],
      chunkIndexVersion: target.versionNumber,
      chunkPhysicalIndex: target.physicalName,
      mappingSchemaVersion: form.mappingSchemaVersion,
      configRevision: target.configRevision,
      entityLinkingVersion: form.entityLinkingVersion,
    });
    ElMessage.success(`已提交批次 ${result.batchId}（${dualBadge({
      chunkIndexVersion: target.versionNumber,
      communityIndexVersion: result.communityIndexVersion,
    } as GraphBatch)}）`);
    selectedBatch.value = result.batchId;
    await load();
  } catch (error) {
    showError(error);
  }
}

async function runAction(run: GraphRun, action: "retry" | "cancel") {
  try {
    await api.graphCommand(`/runs/${run.id}/${action}`);
    ElMessage.success("请求已提交");
    await load();
  } catch (error) {
    showError(error);
  }
}

async function publish(snapshot: GraphSnapshotRow) {
  const pub = publications.value.find(
    p => p.kbId === snapshot.kbId && p.chunkIndexVersion === snapshot.chunkIndexVersion);
  try {
    await api.graphCommand(`/snapshots/${snapshot.id}/publish`, {
      kbId: snapshot.kbId,
      chunkIndexVersion: snapshot.chunkIndexVersion,
      expectedSnapshotId: pub?.activeSnapshotId ?? null,
    });
    ElMessage.success("发布请求已提交（服务端仍执行完整门禁）");
    await load();
  } catch (error) {
    showError(error);
  }
}

async function rollback(pub: GraphPublication) {
  try {
    const result = await ElMessageBox.prompt(
      `回滚 kb=${pub.kbId} Chunk v${pub.chunkIndexVersion} 到历史快照 ID：`,
      "发布回滚", { inputPattern: /^\d+$/, inputErrorMessage: "请输入快照 ID" });
    await api.graphCommand("/publications/rollback", {
      kbId: pub.kbId,
      chunkIndexVersion: pub.chunkIndexVersion,
      targetSnapshotId: Number(result.value),
      expectedSnapshotId: pub.activeSnapshotId,
    });
    ElMessage.success("回滚请求已提交");
    await load();
  } catch (error) {
    if (error !== "cancel") showError(error);
  }
}

async function retire(snapshot: GraphSnapshotRow) {
  try {
    await api.graphCommand(`/snapshots/${snapshot.id}/retire`);
    ElMessage.success("已进入退役流程（先置 DELETING，宽限期后可清理）");
    await load();
  } catch (error) {
    showError(error);
  }
}

async function cleanup(snapshot: GraphSnapshotRow) {
  try {
    await api.graphCommand(`/snapshots/${snapshot.id}/cleanup`);
    ElMessage.success("清理请求已执行（幂等，部分失败可重试）");
    await load();
  } catch (error) {
    showError(error);
  }
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiError ? error.code : "操作失败");
}
</script>
<template>
  <div class="shell">
    <header class="topbar">
      <strong>KWiki · 知识图谱任务</strong>
      <nav class="topnav">
        <router-link to="/">搜索索引管理</router-link>
        <span>{{ auth.user?.username }}</span>
        <el-button text style="color:white" @click="auth.logout();$router.push('/login')">退出</el-button>
      </nav>
    </header>
    <main class="page" v-loading="loading">
      <section class="hero">
        <div>
          <h1>图构建任务</h1>
          <p class="muted">
            从 CHUNK 索引抽取实体与关系，构建 Leiden 社区图谱并生成 COMMUNITY 摘要索引；
            发布默认手动执行，autoPublish 需显式开启。
          </p>
        </div>
      </section>

      <el-result
        v-if="feature === 'disabled'"
        icon="info"
        title="图构建功能未开启"
        sub-title="当前后端未启用知识图谱能力，因此本页没有可展示的数据。">
        <template #extra>
          <div class="enable-guide">
            <p>如需启用：在配置中心 <code>kwiki-local.yaml</code> 中增加以下配置，然后重启后端：</p>
            <pre>kwiki:
  graph:
    enabled: true</pre>
            <el-button type="primary" @click="probe">重新检测</el-button>
          </div>
        </template>
      </el-result>

      <template v-else-if="feature === 'available'">
        <section class="cards" v-if="service">
          <div class="metric">
            <span class="metric-label">图功能</span>
            <strong>{{ service.enabled ? "已启用" : "默认关闭" }}</strong>
          </div>
          <div class="metric">
            <span class="metric-label">每日调度</span>
            <strong class="metric-text">{{ service.scheduleCron }}（{{ service.scheduleZone }}）</strong>
          </div>
          <div class="metric">
            <span class="metric-label">autoPublish</span>
            <strong>{{ service.autoPublish ? "开启" : "关闭（手动发布）" }}</strong>
          </div>
          <div class="metric">
            <span class="metric-label">活动批次</span>
            <strong>{{ service.activeBatchId > 0 ? service.activeBatchId : "无" }}</strong>
          </div>
        </section>

        <el-tabs>
          <el-tab-pane label="提交任务">
            <p class="tab-desc">提交一个图构建批次：服务端为该批次固定 CHUNK / COMMUNITY 双 ES 版本标记。</p>
            <el-form label-width="150px" class="narrow-form">
              <el-form-item label="范围">
                <el-radio-group v-model="form.scopeKind">
                  <el-radio value="KNOWLEDGE_BASE">指定知识库</el-radio>
                  <el-radio value="ALL">全部知识库</el-radio>
                </el-radio-group>
                <div class="hint">每个批次只针对一个知识库；多个知识库请分别提交。</div>
              </el-form-item>
              <el-form-item v-if="form.scopeKind === 'KNOWLEDGE_BASE'" label="知识库">
                <el-select v-model="form.kbId" filterable placeholder="选择要构建图谱的知识库" style="width:100%">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id"
                    :label="`${kb.name}（ID ${kb.id}）`"/>
                </el-select>
              </el-form-item>
              <el-form-item label="Chunk 目标版本">
                <el-select v-model="form.chunkIndexVersion" style="width:100%"
                  placeholder="已预选当前读别名指向的版本，一般无需更改">
                  <el-option v-for="v in chunkVersions" :key="v.versionNumber"
                    :label="`v${v.versionNumber} · ${v.physicalName}${v.selected ? '（当前线上）' : ''}`"
                    :value="v.versionNumber"/>
                </el-select>
                <div class="hint">图谱从该 CHUNK 版本抽取实体；默认使用当前线上读别名指向的版本。</div>
              </el-form-item>
              <el-form-item label="Mapping schema">
                <el-input-number v-model="form.mappingSchemaVersion" :min="1"/>
                <div class="hint">图谱索引 mapping 结构代际；仅当图谱结构升级时才调高，日常保持默认。</div>
              </el-form-item>
              <el-form-item label="实体映射代际">
                <el-input v-model="form.entityLinkingVersion" placeholder="如 v1"/>
                <div class="hint">实体抽取 / 提示词 / 消歧器的整体代际标识，用于追溯哪套逻辑产出了这份图谱。</div>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="submit">提交构建批次</el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="批次与子任务">
            <p class="tab-desc">每个批次按知识库拆成子任务（run）；子任务失败可重试，进行中可取消。</p>
            <el-table :data="batches" highlight-current-row
              @current-change="(row: GraphBatch) => row && selectBatch(row.id)">
              <el-table-column prop="id" label="批次" width="80"/>
              <el-table-column label="版本对（双徽标）" width="260">
                <template #default="{ row }">
                  <strong>{{ dualBadge(row) }}</strong>
                  <div class="muted">{{ communityBadge(row) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="范围" width="150">
                <template #default="{ row }">{{ row.scopeKind === "ALL" ? "全部知识库" : `知识库 ${row.scopeKind}` }}</template>
              </el-table-column>
              <el-table-column prop="state" label="状态" width="130">
                <template #default="{ row }"><el-tag>{{ row.state }}</el-tag></template>
              </el-table-column>
              <el-table-column label="自动发布" width="100">
                <template #default="{ row }">{{ row.autoPublish ? "是" : "否" }}</template>
              </el-table-column>
              <el-table-column prop="requestedBy" label="发起人" width="130"/>
              <el-table-column prop="createdAt" label="创建时间" width="180"/>
            </el-table>
            <h3 v-if="selectedBatch">批次 {{ selectedBatch }} 的子任务</h3>
            <el-table :data="selectedRuns" size="small">
              <el-table-column prop="id" label="runId" width="80"/>
              <el-table-column prop="kbId" label="知识库" width="90"/>
              <el-table-column label="CHUNK / COMMUNITY" width="180">
                <template #default="{ row }">v{{ row.chunkIndexVersion }} / v{{ row.communityIndexVersion }}</template>
              </el-table-column>
              <el-table-column prop="graphVersion" label="graphVersion" width="110"/>
              <el-table-column prop="communityPhysicalIndex" label="社区物理索引" min-width="220"/>
              <el-table-column label="阶段" width="130">
                <template #default="{ row }"><el-tag>{{ row.state }}</el-tag> {{ row.stage }}</template>
              </el-table-column>
              <el-table-column label="计数" width="220">
                <template #default="{ row }">实体 {{ row.entityCount }} · 关系 {{ row.relationCount }} · 社区 {{ row.communityCount }}</template>
              </el-table-column>
              <el-table-column label="操作" width="170">
                <template #default="{ row }">
                  <el-button size="small" @click="runAction(row, 'retry')">重试</el-button>
                  <el-button size="small" type="danger" @click="runAction(row, 'cancel')">取消</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="发布配对">
            <p class="tab-desc">各知识库当前线上生效的图谱快照；可回滚到历史快照。</p>
            <el-table :data="publications">
              <el-table-column prop="kbId" label="知识库" width="90"/>
              <el-table-column prop="chunkIndexVersion" label="CHUNK 版本" width="120"/>
              <el-table-column label="活动快照" width="110">
                <template #default="{ row }">{{ row.activeSnapshotId ?? "未发布" }}</template>
              </el-table-column>
              <el-table-column label="graphVersion" width="110">
                <template #default="{ row }">{{ row.graphVersion ?? "-" }}</template>
              </el-table-column>
              <el-table-column label="COMMUNITY 版本" width="140">
                <template #default="{ row }">{{ row.communityIndexVersion ?? "-" }}</template>
              </el-table-column>
              <el-table-column prop="communityPhysicalIndex" label="社区物理索引" min-width="220">
                <template #default="{ row }">{{ row.communityPhysicalIndex ?? "-" }}</template>
              </el-table-column>
              <el-table-column prop="publishedAt" label="发布时间" width="180"/>
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button size="small" @click="rollback(row)">回滚</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="快照与发布">
            <p class="tab-desc">READY 状态的快照可发布上线；不再需要的快照先退役、宽限期后清理。</p>
            <el-table :data="snapshots" size="small">
              <el-table-column prop="id" label="快照" width="80"/>
              <el-table-column prop="kbId" label="知识库" width="80"/>
              <el-table-column prop="graphVersion" label="graph 版本" width="100"/>
              <el-table-column label="版本对" width="140">
                <template #default="{ row }">v{{ row.chunkIndexVersion }} / v{{ row.communityIndexVersion }}</template>
              </el-table-column>
              <el-table-column prop="communityPhysicalIndex" label="社区物理索引" min-width="200"/>
              <el-table-column prop="state" label="状态" width="110">
                <template #default="{ row }"><el-tag>{{ row.state }}</el-tag></template>
              </el-table-column>
              <el-table-column label="计数" width="200">
                <template #default="{ row }">实体 {{ row.entityCount }} · 社区 {{ row.communityCount }}</template>
              </el-table-column>
              <el-table-column label="操作" min-width="240">
                <template #default="{ row }">
                  <el-button size="small" type="success" :disabled="row.state !== 'READY'" @click="publish(row)">发布</el-button>
                  <el-button size="small" type="warning"
                    :disabled="!['READY', 'RETIRED', 'PUBLISHED'].includes(row.state)" @click="retire(row)">退役</el-button>
                  <el-button size="small" type="danger" :disabled="row.state !== 'DELETING'" @click="cleanup(row)">清理</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="调度与服务">
            <p class="tab-desc">每日定时构建的配置与执行记录；容量上限用于保护大知识库。</p>
            <div v-if="service">
              <el-descriptions :column="3" border>
                <el-descriptions-item label="算法模式">{{ service.algorithmMode }}</el-descriptions-item>
                <el-descriptions-item label="调度">{{ service.scheduleCron }}</el-descriptions-item>
                <el-descriptions-item label="时区">{{ service.scheduleZone }}</el-descriptions-item>
                <el-descriptions-item label="实体上限">{{ service.capacity.maxEntities }}（预警 {{ service.capacity.warnEntities }}）</el-descriptions-item>
                <el-descriptions-item label="关系上限">{{ service.capacity.maxRelations }}（预警 {{ service.capacity.warnRelations }}）</el-descriptions-item>
                <el-descriptions-item label="摘要上限">{{ service.capacity.maxCommunities }}</el-descriptions-item>
              </el-descriptions>
            </div>
            <h3>每日调度记录</h3>
            <el-table :data="schedule" size="small">
              <el-table-column prop="scheduleDate" label="日期" width="140"/>
              <el-table-column prop="status" label="状态" width="160">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'SKIPPED_ACTIVE' ? 'warning' : 'success'">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="关联批次" width="120">
                <template #default="{ row }">{{ row.linkedBatchId ?? "-" }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间"/>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="审计">
            <p class="tab-desc">管理命令（提交 / 发布 / 回滚 / 退役等）的完整操作轨迹。</p>
            <el-table :data="audits" size="small">
              <el-table-column prop="createdAt" label="时间" width="180"/>
              <el-table-column prop="operator" label="操作者" width="130"/>
              <el-table-column prop="action" label="动作" width="180"/>
              <el-table-column label="批次" width="90">
                <template #default="{ row }">{{ row.batchId ?? "-" }}</template>
              </el-table-column>
              <el-table-column label="run" width="90">
                <template #default="{ row }">{{ row.runId ?? "-" }}</template>
              </el-table-column>
              <el-table-column prop="resultState" label="结果状态" width="130"/>
              <el-table-column prop="summary" label="摘要" min-width="260"/>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </main>
  </div>
</template>
