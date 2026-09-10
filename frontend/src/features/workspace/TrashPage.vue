<script setup lang="ts">
/**
 * 回收站界面。工作空间视图（`/trash`）：已归档的知识库；
 * 知识库视图（`/knowledge-bases/:kbId/trash`）：该知识库的页面批次
 * 行。表格行展示操作人、归档 / 过期时间、索引同步状态以及
 * 可恢复性；恢复操作将 409/410 原因以可读的内联提示展示。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, errorMessage } from '../wiki/api';

type TrashItem = {
  batchId: number;
  batchUuid: string;
  resourceType: string;
  rootResourceId: number;
  title: string;
  operatorName: string;
  archivedAt: string;
  purgeAfter: string;
  restorable: boolean;
  indexSyncStatus: string;
  itemCount: number;
};

const route = useRoute();
const router = useRouter();
const items = ref<TrashItem[]>([]);
const nextCursor = ref<number | null>(null);
const loading = ref(false);
const error = ref('');
const notice = ref('');
const restoringId = ref<number | null>(null);

const kbId = computed(() => {
  const raw = Array.isArray(route.params.kbId) ? route.params.kbId[0] : route.params.kbId;
  return raw ? Number(raw) : null;
});

const heading = computed(() => (kbId.value ? '知识库回收站' : '已归档的知识库'));

async function load(reset = true) {
  loading.value = true;
  error.value = '';
  try {
    const query = new URLSearchParams({ limit: '20' });
    if (kbId.value) query.set('kbId', String(kbId.value));
    if (!reset && nextCursor.value) query.set('cursor', String(nextCursor.value));
    const page = await api.json<{ items: TrashItem[]; nextCursor: number | null }>(`/trash?${query}`);
    items.value = reset ? page.items : [...items.value, ...page.items];
    nextCursor.value = page.nextCursor;
  } catch (e) {
    error.value = errorMessage(e, '回收站加载失败，请重试');
  } finally {
    loading.value = false;
  }
}

async function restore(item: TrashItem) {
  if (restoringId.value != null || !item.restorable) return;
  restoringId.value = item.batchId;
  notice.value = '';
  error.value = '';
  try {
    const result = await api.post<{ message?: string }>(`/trash/${item.batchId}/restore`, {});
    notice.value = result.message || '已恢复，正在重建索引';
    await load(true);
  } catch (e) {
    error.value = errorMessage(e, '恢复失败，请稍后重试');
  } finally {
    restoringId.value = null;
  }
}

function formatTime(value: string): string {
  try {
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return value;
  }
}

function syncLabel(status: string): string {
  return status === 'SYNCED' ? '索引已清理' : '索引清理重试中';
}

onMounted(() => void load(true));
watch(kbId, () => void load(true));
</script>

<template>
  <main class="trash-page" data-testid="trash-page">
    <nav class="crumb">
      <RouterLink to="/knowledge-bases">知识库</RouterLink>
      <span v-if="kbId">/ <RouterLink :to="`/knowledge-bases/${kbId}`">工作区</RouterLink></span>
      <span>/ 回收站</span>
    </nav>
    <header class="head">
      <h1>{{ heading }}</h1>
      <p>归档内容保留 7 天，到期后由系统每日 01:00 物理清理；归档期间立即停止被检索。</p>
    </header>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>
    <div v-if="loading && !items.length" class="loading">正在加载回收站…</div>
    <p v-else-if="!items.length" class="empty">回收站为空</p>
    <table v-else class="trash-table">
      <thead>
        <tr>
          <th>名称</th>
          <th>类型</th>
          <th>归档者</th>
          <th>归档时间</th>
          <th>到期时间</th>
          <th>索引状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.batchId">
          <td class="title" :title="item.title">{{ item.title }}</td>
          <td>{{ item.resourceType === 'KNOWLEDGE_BASE' ? '知识库' : '页面' }}</td>
          <td>{{ item.operatorName }}</td>
          <td>{{ formatTime(item.archivedAt) }}</td>
          <td>{{ formatTime(item.purgeAfter) }}</td>
          <td>{{ syncLabel(item.indexSyncStatus) }}</td>
          <td>
            <button
              type="button"
              class="ui-button"
              :disabled="!item.restorable || restoringId === item.batchId"
              :title="item.restorable ? '恢复到归档前的位置' : '保留期已过或已清理，无法恢复'"
              @click="restore(item)"
            >
              {{ restoringId === item.batchId ? '恢复中…' : item.restorable ? '恢复' : '不可恢复' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <footer v-if="nextCursor" class="more">
      <button type="button" class="ui-button" :disabled="loading" @click="load(false)">
        加载更多
      </button>
    </footer>
    <RouterLink v-if="kbId" class="back" :to="`/knowledge-bases/${kbId}`">返回工作区</RouterLink>
  </main>
</template>

<style scoped>
.trash-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 30px 40px 80px;
}
.crumb {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: #92a095;
}
.crumb a {
  color: #6d8a76;
  text-decoration: none;
}
.head h1 {
  font-size: 24px;
  margin: 18px 0 8px;
}
.head p {
  color: #97a499;
  font-size: 12px;
  line-height: 1.8;
  margin: 0 0 22px;
}
.loading,
.empty {
  color: #8c9f90;
  font-size: 13px;
  padding: 30px 0;
  text-align: center;
}
.trash-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.trash-table th {
  text-align: left;
  color: #a0aca3;
  font-size: 11px;
  font-weight: 500;
  padding: 10px 8px;
  border-bottom: 1px solid #e9eeea;
}
.trash-table td {
  padding: 13px 8px;
  border-bottom: 1px solid #edf2ee;
  color: #5b6f63;
}
.title {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #2f4638;
  font-weight: 500;
}
.more {
  margin-top: 16px;
  text-align: center;
}
.back {
  display: inline-block;
  margin-top: 26px;
  font-size: 12px;
  color: #43885a;
  text-decoration: none;
}
</style>
