<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api, ApiError, type EvalDataset, type EvalImportResult, type EvalRunStatus } from "../api";
import { useAuth } from "../auth";
import {
  EVAL_DATASETS, EVAL_LAYERS, LOSS_LAYERS, RUN_STATE_LABELS,
  canCancel, canDelete, canDownload, canResume, canRetryFailed, domainLabel, downloadJson, evalErrorMessage, formatTime,
  groupRows, layerRows, lossRows, percent, progressPercent, runErrorMessage,
} from "../evalReport";

/** run 状态与逐题结果保存在服务端（Redis），页面记住当前查看的 runId，刷新后恢复。 */
const RUN_KEY = "kwiki_admin_eval_run";
const KB_KEY = "kwiki_admin_eval_kb";

const auth = useAuth();
const knowledgeBases = ref<{ id: number; name: string }[]>([]);
const kbLoaded = ref(false);
const kbId = ref<number | null>(Number(localStorage.getItem(KB_KEY)) || null);
const importing = ref(false);
const importResult = ref<EvalImportResult | null>(null);
const syncing = ref(false);
const syncResult = ref<{ items: number; written: number; skipped: number } | null>(null);
const starting = ref(false);
const form = reactive({ dataset: "50q" as EvalDataset, runName: "", notes: "", localOnly: false });

const runId = ref<string | null>(localStorage.getItem(RUN_KEY));
const run = ref<EvalRunStatus | null>(null);
const expired = ref(false);
const groupBy = ref<"category" | "domain">("category");
const groupMetric = ref<"coverage" | "fullHit">("coverage");
const history = ref<EvalRunStatus[]>([]);
const historyLoading = ref(false);
const acting = ref(false);
type ConfirmAction = "cancel" | "resume" | "retry" | "delete";
const confirm = reactive({ visible: false, action: "cancel" as ConfirmAction, run: null as EvalRunStatus | null, langfuse: true });
const CONFIRM_TITLES: Record<ConfirmAction, string> = { cancel: "中断评测", resume: "继续评测", retry: "重试出错题", delete: "删除评测" };
let timer: number | undefined;
let pollDelay = 3000;
let disposed = false;
// 历史列表有排队或运行中的评测时静默刷新（可能由其他实例执行，当前查看的 run 之外也要更新）
const HISTORY_REFRESH_MS = 5000;
let historyTimer: number | undefined;

/** 排队中与运行中都需要持续轮询状态。 */
const running = computed(() => run.value?.state === "RUNNING" || run.value?.state === "QUEUED");
const summary = computed(() => run.value?.summary ?? null);
const overall = computed(() => layerRows(summary.value?.overall));
const groups = computed(() => groupBy.value === "category"
  ? groupRows(summary.value?.byCategory)
  : groupRows(summary.value?.byDomain, domainLabel));
const losses = computed(() => lossRows(summary.value?.lossMatrix));

watch(kbId, value => {
  if (value) localStorage.setItem(KB_KEY, String(value)); else localStorage.removeItem(KB_KEY);
});

function handleAuth(error: unknown) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    auth.logout();
    location.assign("/admin/login");
    return true;
  }
  return false;
}

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await api.adminKnowledgeBases();
  } catch (error) {
    if (handleAuth(error)) return;
    knowledgeBases.value = [];
  } finally {
    kbLoaded.value = true;
  }
}

async function importCorpus() {
  if (!kbId.value) return;
  importing.value = true;
  try {
    importResult.value = await api.evalImportCorpus(kbId.value);
    ElMessage.success("评测语料导入完成");
  } catch (error) {
    if (!handleAuth(error)) ElMessage.error(evalErrorMessage(error, "导入评测语料失败"));
  } finally {
    importing.value = false;
  }
}

async function syncDatasets() {
  syncing.value = true;
  try {
    syncResult.value = await api.evalSyncDatasets();
    ElMessage.success("测试集已同步到 Langfuse");
  } catch (error) {
    if (!handleAuth(error)) ElMessage.error(evalErrorMessage(error, "同步测试集失败"));
  } finally {
    syncing.value = false;
  }
}

async function start() {
  if (!kbId.value) return;
  starting.value = true;
  try {
    const status = await api.evalStartRun({
      dataset: form.dataset,
      kbId: kbId.value,
      runName: form.runName.trim() || undefined,
      notes: form.notes.trim() || undefined,
      localOnly: form.localOnly,
    });
    remember(status);
    ElMessage.success(status.state === "QUEUED" ? "已加入队列，等待执行" : "评测已启动");
    pollDelay = 3000;
    schedule();
    void loadHistory();
  } catch (error) {
    if (!handleAuth(error)) ElMessage.error(evalErrorMessage(error, "启动评测失败"));
  } finally {
    starting.value = false;
  }
}

function remember(status: EvalRunStatus) {
  run.value = status;
  runId.value = status.runId;
  expired.value = false;
  localStorage.setItem(RUN_KEY, status.runId);
}

function forget() {
  window.clearTimeout(timer);
  run.value = null;
  runId.value = null;
  expired.value = false;
  localStorage.removeItem(RUN_KEY);
}

async function poll() {
  const id = runId.value;
  if (!id) return;
  try {
    const status = await api.evalRunStatus(id);
    if (id !== runId.value) return;
    const stateChanged = run.value != null && run.value.state !== status.state;
    run.value = status;
    const index = history.value.findIndex((row) => row.runId === status.runId);
    if (index >= 0) history.value.splice(index, 1, status);
    // 状态变化会影响其他行的排队位次，整表刷新
    if (stateChanged) void loadHistory(true);
    expired.value = false;
    pollDelay = 3000;
  } catch (error) {
    if (handleAuth(error)) return;
    if (error instanceof Error && error.message.startsWith("eval-run-not-found")) {
      run.value = null;
      expired.value = true;
      return;
    }
    // 网络或服务端临时错误：退避重试，上限 30 秒
    pollDelay = Math.min(pollDelay * 2, 30000);
    schedule(true);
    return;
  }
  schedule();
}

/** 只有运行中的 run（或查询临时失败需重试时）才续期轮询；页面卸载后不再续期。 */
function schedule(retry = false) {
  window.clearTimeout(timer);
  if (disposed || !(retry || running.value)) return;
  timer = window.setTimeout(() => void poll(), pollDelay);
}

async function loadHistory(silent = false) {
  window.clearTimeout(historyTimer);
  if (!silent) historyLoading.value = true;
  try {
    history.value = await api.evalListRuns();
  } catch (error) {
    if (!handleAuth(error) && !silent) ElMessage.error(evalErrorMessage(error, "加载历史评测失败"));
  } finally {
    if (!silent) historyLoading.value = false;
    scheduleHistory();
  }
}
function scheduleHistory() {
  window.clearTimeout(historyTimer);
  const active = history.value.some((row) => row.state === "RUNNING" || row.state === "QUEUED");
  if (disposed || !active) return;
  historyTimer = window.setTimeout(() => void loadHistory(true), HISTORY_REFRESH_MS);
}

/** 在「运行状态」中查看某个历史 run。 */
function view(target: EvalRunStatus) {
  remember(target);
  pollDelay = 3000;
  void poll();
}

function ask(action: ConfirmAction, target: EvalRunStatus) {
  confirm.action = action;
  confirm.run = target;
  confirm.langfuse = !target.localOnly;
  confirm.visible = true;
}

async function confirmAction() {
  const target = confirm.run;
  if (!target) return;
  acting.value = true;
  try {
    if (confirm.action === "cancel") {
      const status = await api.evalCancelRun(target.runId);
      if (runId.value === target.runId) run.value = status;
      ElMessage.success(status.state === "RUNNING" ? "已发送中断请求，稍后生效" : "评测已中断");
      if (runId.value === target.runId) schedule();    } else if (confirm.action === "resume") {
      const status = await api.evalResumeRun(target.runId);
      remember(status);
      ElMessage.success("评测已继续");
      pollDelay = 3000;
      schedule();
    } else if (confirm.action === "retry") {
      const status = await api.evalRetryFailedRun(target.runId);
      remember(status);
      ElMessage.success("出错题已重新排队评测");
      pollDelay = 3000;
      schedule();
    } else {
      const result = await api.evalDeleteRun(target.runId, confirm.langfuse && !target.localOnly);
      if (runId.value === target.runId) forget();
      if (result.langfuseError) {
        ElMessage.warning(`本地记录已删除，但 Langfuse 删除失败：${result.langfuseError}，请到 Langfuse 控制台手动清理`);
      } else {
        ElMessage.success(result.langfuseDeleted ? "已删除（Langfuse 数据异步删除中）" : "已删除");
      }
    }
    confirm.visible = false;
  } catch (error) {
    if (!handleAuth(error)) ElMessage.error(evalErrorMessage(error, `${CONFIRM_TITLES[confirm.action]}失败`));
  } finally {
    acting.value = false;
    void loadHistory();
  }
}

async function download(target: EvalRunStatus) {
  try {
    downloadJson(target.runName, await api.evalRunReport(target.runId));
  } catch (error) {
    if (!handleAuth(error)) ElMessage.error(evalErrorMessage(error, "下载报告失败"));
  }
}

onMounted(() => {
  void loadKnowledgeBases();
  void loadHistory();
  if (runId.value) void poll();
});
onBeforeUnmount(() => {
  disposed = true;
  window.clearTimeout(timer);
  window.clearTimeout(historyTimer);
});
</script>

<template>
  <main class="page">
    <section class="hero">
      <div>
        <h1>RAG 评测</h1>
        <p class="muted">
          基于 DragonBall 中文语料与 50/100/200 题嵌套测试集，按关键词逐层统计召回（BM25、向量、候选池、融合排序、最终上下文与回答），
          结果写入 Langfuse，运行状态与逐题结果保存在服务端，可随时中断、继续或下载 JSON 报告。建议使用专用知识库，只放公开评测数据。
        </p>
      </div>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2>1. 准备语料与测试集</h2>
        <span class="hint">导入幂等，可重复执行；导入后需等待索引完成再启动评测。</span>
      </header>
      <div class="row">
        <label class="field-label">评测知识库</label>
        <el-select v-if="knowledgeBases.length" v-model="kbId" filterable placeholder="选择知识库" style="width: 280px" aria-label="评测知识库">
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="`${kb.name}（#${kb.id}）`" :value="kb.id" />
        </el-select>
        <template v-else-if="kbLoaded">
          <el-input-number v-model="kbId" :min="1" :controls="false" placeholder="输入 kbId" style="width: 180px" aria-label="知识库 ID" />
          <span class="hint">未获取到知识库列表，请手动输入 kbId</span>
        </template>
        <el-button type="primary" :disabled="!kbId" :loading="importing" @click="importCorpus">导入评测语料</el-button>
        <el-button :loading="syncing" @click="syncDatasets">同步测试集到 Langfuse</el-button>
      </div>
      <p v-if="importResult" class="result">
        语料导入：新建 <strong>{{ importResult.createdPageIds.length }}</strong> 篇，跳过（已存在）<strong>{{ importResult.skippedTitles.length }}</strong> 篇
      </p>
      <p v-if="syncing" class="hint">Langfuse 接口有限流（约 100 次/分钟），首次同步约 350 条需要 4~5 分钟，请保持页面打开；再次同步只写有变化的条目。</p>
      <p v-if="syncResult" class="result">已同步 <strong>{{ syncResult.items }}</strong> 条测试题到 Langfuse 数据集（kwiki-rag-50q / 100q / 200q），其中新写入 <strong>{{ syncResult.written }}</strong> 条，内容未变跳过 <strong>{{ syncResult.skipped }}</strong> 条</p>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2>2. 启动评测</h2>
        <span class="hint">提交后进入队列等待执行（集群同时执行的评测数有上限）；每次检索改动后用相同测试集跑一次，并在备注里写清改动。</span>
      </header>
      <el-form label-width="96px" class="start-form" @submit.prevent>
        <el-form-item label="测试集">
          <el-radio-group v-model="form.dataset">
            <el-radio-button v-for="item in EVAL_DATASETS" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="运行名称">
          <el-input v-model="form.runName" placeholder="留空自动生成，如 baseline-50q" maxlength="100" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" placeholder="本次改动说明，如「ik 分词」「rerank」" maxlength="500" />
        </el-form-item>
        <el-form-item label="仅本地">
          <el-switch v-model="form.localOnly" aria-label="仅本地" />
          <span class="hint switch-hint">开启后不上报 Langfuse，结果只保存在服务端（未配置 Langfuse 时必须开启）</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!kbId" :loading="starting" @click="start">开始评测</el-button>
          <span v-if="!kbId" class="hint switch-hint">请先选择知识库</span>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2>3. 历史评测</h2>
        <div class="toggles">
          <span class="hint">运行状态与逐题结果保存在服务端，重启后可继续未完成的评测。</span>
          <el-button text size="small" :loading="historyLoading" @click="loadHistory()">刷新</el-button>
        </div>
      </header>
      <el-table :data="history" row-key="runId" size="small" empty-text="暂无评测记录">
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.runName }}</strong>
            <el-tag v-if="row.localOnly" size="small" type="info" class="inline-tag">仅本地</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="测试集" prop="dataset" width="80" />
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <el-tag :type="RUN_STATE_LABELS[row.state as EvalRunStatus['state']].type" size="small">{{ RUN_STATE_LABELS[row.state as EvalRunStatus['state']].label }}</el-tag>
            <div v-if="row.state === 'QUEUED'" class="muted tiny">前面还有 {{ row.queuePosition ?? 0 }} 个</div>
            <div v-else-if="row.state === 'RUNNING' && row.ownerInstanceId" class="muted tiny">{{ row.ownerInstanceId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="100">
          <template #default="{ row }">{{ row.completed }} / {{ row.total }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="view(row)">查看</el-button>
            <el-button v-if="canCancel(row)" link type="warning" size="small" @click="ask('cancel', row)">中断</el-button>
            <el-button v-if="canResume(row)" link type="primary" size="small" @click="ask('resume', row)">继续</el-button>
            <el-button v-if="canRetryFailed(row)" link type="primary" size="small" @click="ask('retry', row)">重试出错题</el-button>
            <el-button v-if="canDownload(row)" link size="small" @click="download(row)">下载 JSON</el-button>
            <el-button v-if="canDelete(row)" link type="danger" size="small" @click="ask('delete', row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel" v-if="runId || expired">
      <header class="panel-head">
        <h2>4. 运行状态</h2>
        <el-button text size="small" @click="forget">清除记录</el-button>
      </header>
      <el-alert v-if="expired" type="warning" :closable="false" show-icon
        title="评测记录不存在"
        description="该评测可能已被删除；可在「历史评测」中查看其他记录。" />
      <template v-else-if="run">
        <div class="run-meta">
          <strong>{{ run.runName }}</strong>
          <el-tag :type="RUN_STATE_LABELS[run.state].type">{{ RUN_STATE_LABELS[run.state].label }}</el-tag>
          <span class="muted">测试集 {{ run.dataset }} · {{ run.completed }} / {{ run.total }} 题</span>
          <span v-if="run.state === 'QUEUED'" class="muted">前面还有 {{ run.queuePosition ?? 0 }} 个评测</span>
          <span v-if="run.state === 'RUNNING' && run.ownerInstanceId" class="muted">执行实例 {{ run.ownerInstanceId }}</span>
          <span class="run-actions">
            <el-button v-if="canCancel(run)" size="small" type="warning" plain @click="ask('cancel', run)">中断</el-button>
            <el-button v-if="canResume(run)" size="small" type="primary" plain @click="ask('resume', run)">继续</el-button>
            <el-button v-if="canRetryFailed(run)" size="small" type="primary" plain @click="ask('retry', run)">重试出错题</el-button>
            <el-button v-if="canDownload(run)" size="small" @click="download(run)">下载 JSON</el-button>
            <el-button v-if="canDelete(run)" size="small" type="danger" plain @click="ask('delete', run)">删除</el-button>
          </span>
        </div>
        <el-progress :percentage="progressPercent(run)" :status="run.state === 'FAILED' ? 'exception' : run.state === 'RUNNING' || run.state === 'QUEUED' || run.state === 'CANCELLED' ? undefined : 'success'" />
        <el-alert v-if="run.error" class="run-error" :type="run.state === 'FAILED' ? 'error' : 'warning'" :closable="false" show-icon :title="runErrorMessage(run.error) ?? ''" />
        <el-alert v-if="run.reportPartial" class="run-error" type="warning" :closable="false" show-icon title="部分结果未能上报到 Langfuse，结果已完整保存在服务端，可下载 JSON 查看" />
      </template>
      <p v-else class="muted">正在查询评测状态…</p>
    </section>

    <el-dialog v-model="confirm.visible" :title="CONFIRM_TITLES[confirm.action]" width="460px" append-to-body>
      <template v-if="confirm.run">
        <p v-if="confirm.action === 'cancel' && confirm.run.state === 'QUEUED'">
          确定中断「{{ confirm.run.runName }}」？该评测仍在排队，取消后可随时继续。
        </p>
        <p v-else-if="confirm.action === 'cancel'">
          确定中断「{{ confirm.run.runName }}」？正在执行的题会立即取消、不计入结果；已完成的 {{ confirm.run.completed }} 题会保留，之后可继续评测剩余题目。
        </p>
        <p v-else-if="confirm.action === 'resume'">
          继续「{{ confirm.run.runName }}」：只评测尚未完成的 {{ Math.max(confirm.run.total - confirm.run.completed, 0) }} 题（中断时正在执行的题会重新召回、重新生成），沿用同一个运行名称，最终汇总包含全部题目。
        </p>
        <p v-else-if="confirm.action === 'retry'">
          重试「{{ confirm.run.runName }}」的 {{ confirm.run.summary?.errors ?? 0 }} 道出错题：删除这些题的结果后重新召回、重新生成，<template v-if="confirm.run.state === 'CANCELLED' || confirm.run.state === 'FAILED'">尚未完成的题也会一并评测，</template>其余题的结果保留；沿用同一个运行名称，Langfuse 上对应的 trace 会被覆盖，最终汇总包含全部题目。
        </p>
        <template v-else>
          <p>确定删除「{{ confirm.run.runName }}」？服务端保存的运行状态与全部逐题结果将被删除，且不可恢复。</p>
          <el-checkbox v-model="confirm.langfuse" :disabled="confirm.run.localOnly">同时删除 Langfuse 上的 trace 与 dataset run</el-checkbox>
          <p class="hint">{{ confirm.run.localOnly ? "该评测为仅本地运行，没有上报 Langfuse。" : "Langfuse 删除为异步执行且不可恢复；不勾选则 Langfuse 上的数据保留。" }}</p>
        </template>
      </template>
      <template #footer>
        <el-button @click="confirm.visible = false">取消</el-button>
        <el-button :type="confirm.action === 'delete' ? 'danger' : 'primary'" :loading="acting" @click="confirmAction">确定{{ CONFIRM_TITLES[confirm.action].slice(0, 2) }}</el-button>
      </template>
    </el-dialog>

    <template v-if="run && summary">
      <section class="cards">
        <div class="metric">
          <span class="metric-label">计分题数</span>
          <strong>{{ summary.scored }} / {{ summary.total }}</strong>
          <div class="hint">另有出错 {{ summary.errors }} 题、无关无解 {{ summary.negatives }} 题（均不计覆盖率）</div>
        </div>
        <div class="metric">
          <span class="metric-label">负样本拒答正确率</span>
          <strong>{{ summary.negatives ? percent(summary.refusalAccuracy) : "-" }}</strong>
          <div class="hint">共 {{ summary.negatives }} 道无关无解题</div>
        </div>
        <div class="metric">
          <span class="metric-label">回答覆盖率</span>
          <strong>{{ percent(summary.overall?.answer?.coverage) }}</strong>
          <div class="hint">完全命中 {{ percent(summary.overall?.answer?.fullHit) }}</div>
        </div>
        <div class="metric">
          <span class="metric-label">上下文增益</span>
          <strong>{{ summary.contextGains }}</strong>
          <div class="hint">基线候选池没有、改写/图增强后进入上下文的关键词组</div>
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>各层召回</h2>
          <span class="hint">覆盖率 = 关键词覆盖率均值；完全命中率 = 全部关键词都命中的题占比。chunk 层与上下文只认来源文档片段。</span>
        </header>
        <el-table :data="overall" row-key="key" size="small">
          <el-table-column label="层" min-width="200">
            <template #default="{ row }">
              <strong>{{ row.key }}</strong>
              <div class="muted">{{ row.label }} · {{ row.hint }}</div>
            </template>
          </el-table-column>
          <el-table-column label="覆盖率" width="160">
            <template #default="{ row }">
              <div class="bar-cell"><span>{{ percent(row.coverage) }}</span><el-progress :percentage="Math.round((row.coverage ?? 0) * 100)" :show-text="false" :stroke-width="6" /></div>
            </template>
          </el-table-column>
          <el-table-column label="完全命中率" width="160">
            <template #default="{ row }">
              <div class="bar-cell"><span>{{ percent(row.fullHit) }}</span><el-progress :percentage="Math.round((row.fullHit ?? 0) * 100)" :show-text="false" :stroke-width="6" /></div>
            </template>
          </el-table-column>
          <el-table-column label="题数" width="90" prop="cases" />
        </el-table>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>分组指标</h2>
          <div class="toggles">
            <el-radio-group v-model="groupBy" size="small">
              <el-radio-button value="category">按题型</el-radio-button>
              <el-radio-button value="domain">按领域</el-radio-button>
            </el-radio-group>
            <el-radio-group v-model="groupMetric" size="small">
              <el-radio-button value="coverage">覆盖率</el-radio-button>
              <el-radio-button value="fullHit">完全命中率</el-radio-button>
            </el-radio-group>
          </div>
        </header>
        <el-table :data="groups" row-key="name" size="small">
          <el-table-column :label="groupBy === 'category' ? '题型' : '领域'" prop="name" min-width="150" fixed />
          <el-table-column label="题数" prop="cases" width="70" />
          <el-table-column v-for="layer in EVAL_LAYERS" :key="layer.key" :label="layer.key" min-width="96" align="right">
            <template #default="{ row }">{{ percent(row.metrics[layer.key]?.[groupMetric]) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>首次丢失层矩阵</h2>
          <span class="hint">每个漏召关键词组按链路记录它第一次丢失的位置，数值为关键词组数；用于判断问题集中在哪一层。</span>
        </header>
        <el-table :data="[...losses.rows, losses.totals]" row-key="category" size="small" :row-class-name="({ row }: { row: { category: string } }) => row === losses.totals ? 'total-row' : ''">
          <el-table-column label="题型" prop="category" min-width="150" />
          <el-table-column v-for="loss in LOSS_LAYERS" :key="loss.key" min-width="110" align="right">
            <template #header>
              <el-tooltip :content="loss.hint" placement="top"><span>{{ loss.label }}</span></el-tooltip>
            </template>
            <template #default="{ row }">{{ row.counts[loss.key] ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="合计" prop="total" width="80" align="right" />
        </el-table>
      </section>
    </template>
  </main>
</template>

<style scoped>
.panel {
  margin-bottom: 20px;
  padding: 18px 20px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 16px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--k-ink);
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.field-label {
  color: var(--k-ink-2);
  font-size: 13px;
}

.result {
  margin: 12px 0 0;
  color: var(--k-ink-2);
}

.result code {
  word-break: break-all;
}

.start-form {
  max-width: 720px;
}

.switch-hint {
  margin-left: 10px;
}

.toggles {
  display: flex;
  gap: 10px;
}

.run-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.run-error {
  margin-top: 12px;
}

.run-actions {
  display: inline-flex;
  gap: 6px;
  margin-left: auto;
}

.inline-tag {
  margin-left: 6px;
}

.tiny {
  font-size: 12px;
  line-height: 1.4;
}

.bar-cell {
  display: grid;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

:deep(.total-row) td {
  font-weight: 600;
  background: var(--k-surface);
}
</style>
