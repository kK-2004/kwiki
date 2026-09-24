<script setup lang="ts">
/**
 * 回收站界面。工作空间视图（`/trash`）：用户可管理的知识库和 Wiki 页面批次；
 * 知识库视图（`/knowledge-bases/:kbId/trash`）：该知识库的归档批次
 * 行。表格行展示操作人、归档 / 过期时间、索引同步状态以及
 * 可恢复性；恢复和永久删除操作将 409/410 原因以可读的内联提示展示。
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
const deletingId = ref<number | null>(null);
const selectedIds = ref(new Set<number>());
const deleteTargets = ref<TrashItem[]>([]);
const deleteError = ref('');

const kbId = computed(() => {
  const raw = Array.isArray(route.params.kbId) ? route.params.kbId[0] : route.params.kbId;
  return raw ? Number(raw) : null;
});

const heading = computed(() => (kbId.value ? '知识库回收站' : '回收站'));
const selectedItems = computed(() => items.value.filter(item => selectedIds.value.has(item.batchId)));
const allVisibleSelected = computed(() => items.value.length > 0
  && items.value.every(item => selectedIds.value.has(item.batchId)));
const deleteTarget = computed(() => deleteTargets.value.length === 1 ? deleteTargets.value[0] : null);
const deleting = computed(() => deletingId.value != null);

async function load(reset = true) {
  loading.value = true;
  error.value = '';
  try {
    const query = new URLSearchParams({ limit: '20' });
    if (kbId.value) query.set('kbId', String(kbId.value));
    if (!reset && nextCursor.value) query.set('cursor', String(nextCursor.value));
    const page = await api.json<{ items: TrashItem[]; nextCursor: number | null }>(`/trash?${query}`);
    items.value = reset ? page.items : [...items.value, ...page.items];
    if (reset) selectedIds.value = new Set();
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

function requestDelete(item: TrashItem) {
  if (deleting.value) return;
  deleteError.value = '';
  deleteTargets.value = [item];
}

function requestBatchDelete() {
  if (deleting.value || !selectedItems.value.length) return;
  deleteError.value = '';
  deleteTargets.value = [...selectedItems.value];
}

function cancelDelete() {
  if (deleting.value) return;
  deleteTargets.value = [];
  deleteError.value = '';
}

async function confirmDelete() {
  const targets = [...deleteTargets.value];
  if (!targets.length || deleting.value) return;
  deletingId.value = targets.length === 1 ? targets[0].batchId : -1;
  deleteError.value = '';
  notice.value = '';
  error.value = '';
  try {
    const result = targets.length === 1
      ? await api.delete<{ message?: string }>(`/trash/${targets[0].batchId}`)
      : await api.post<{ message?: string }>('/trash/batch-delete', {
          batchIds: targets.map(item => item.batchId),
        });
    notice.value = result?.message || '已永久删除，内容不可恢复';
    deleteTargets.value = [];
    await load(true);
  } catch (e) {
    deleteError.value = errorMessage(e, '永久删除失败，请稍后重试');
  } finally {
    deletingId.value = null;
  }
}

function toggleSelected(batchId: number, checked: boolean) {
  const next = new Set(selectedIds.value);
  if (checked) next.add(batchId);
  else next.delete(batchId);
  selectedIds.value = next;
}

function toggleAllVisible(checked: boolean) {
  const next = new Set(selectedIds.value);
  for (const item of items.value) {
    if (checked) next.add(item.batchId);
    else next.delete(item.batchId);
  }
  selectedIds.value = next;
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
      <p>归档内容保留 7 天，到期后由系统每日 01:00 物理清理；也可确认后立即永久删除。归档期间立即停止被检索。</p>
    </header>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>
    <div v-if="items.length" class="bulk-actions">
      <span>已选择 {{ selectedItems.length }} 项</span>
      <button
        type="button"
        class="ui-button danger"
        data-testid="trash-batch-delete"
        :disabled="!selectedItems.length || deleting || restoringId != null"
        @click="requestBatchDelete"
      >
        批量永久删除
      </button>
    </div>
    <div v-if="loading && !items.length" class="loading">正在加载回收站…</div>
    <p v-else-if="!items.length" class="empty">回收站为空</p>
    <table v-else class="trash-table">
      <thead>
        <tr>
          <th class="select-cell">
            <input
              type="checkbox"
              aria-label="选择全部回收站项目"
              :checked="allVisibleSelected"
              :disabled="deleting || restoringId != null"
              @change="toggleAllVisible(($event.target as HTMLInputElement).checked)"
            >
          </th>
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
          <td class="select-cell">
            <input
              type="checkbox"
              :aria-label="`选择 ${item.title}`"
              :checked="selectedIds.has(item.batchId)"
              :disabled="deleting || restoringId != null"
              @change="toggleSelected(item.batchId, ($event.target as HTMLInputElement).checked)"
            >
          </td>
          <td class="title" :title="item.title">{{ item.title }}</td>
          <td>{{ item.resourceType === 'KNOWLEDGE_BASE' ? '知识库' : '页面' }}</td>
          <td>{{ item.operatorName }}</td>
          <td>{{ formatTime(item.archivedAt) }}</td>
          <td>{{ formatTime(item.purgeAfter) }}</td>
          <td>{{ syncLabel(item.indexSyncStatus) }}</td>
          <td>
            <div class="actions">
              <button
                type="button"
                class="ui-button"
                :disabled="!item.restorable || restoringId != null || deletingId != null"
                :title="item.restorable ? '恢复到归档前的位置' : '保留期已过或已清理，无法恢复'"
                @click="restore(item)"
              >
                {{ restoringId === item.batchId ? '恢复中…' : item.restorable ? '恢复' : '不可恢复' }}
              </button>
              <button
                type="button"
                class="ui-button danger"
                :disabled="deletingId != null || restoringId != null"
                title="永久删除，无法恢复"
                :data-testid="`trash-delete-${item.batchId}`"
                @click="requestDelete(item)"
              >
                {{ deletingId === item.batchId ? '删除中…' : '删除' }}
              </button>
            </div>
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

    <div v-if="deleteTargets.length" class="confirm-overlay" data-testid="trash-delete-confirm">
      <section class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="trash-delete-title">
        <h2 id="trash-delete-title">
          {{ deleteTarget ? `永久删除「${deleteTarget.title}」？` : `永久删除选中的 ${deleteTargets.length} 项？` }}
        </h2>
        <p>该操作会立即删除所选归档内容、下级页面、附件及其索引，且无法恢复。确定继续吗？</p>
        <p v-if="deleteError" class="ui-error" role="alert">{{ deleteError }}</p>
        <footer>
          <button type="button" class="ui-button" :disabled="deletingId != null" @click="cancelDelete">取消</button>
          <button type="button" class="ui-button danger" data-testid="trash-delete-submit" :disabled="deletingId != null" @click="confirmDelete">
            {{ deletingId != null ? '删除中…' : '确认永久删除' }}
          </button>
        </footer>
      </section>
    </div>
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
.bulk-actions {
  min-height: 42px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #e7ede8;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #718278;
  font-size: 12px;
}
.select-cell {
  width: 34px;
  text-align: center !important;
}
.select-cell input {
  accent-color: #43885a;
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
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.ui-button.danger {
  color: #a55f5f;
  border-color: #e8cccc;
}
.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #203d2b44;
  display: grid;
  place-items: center;
  padding: 20px;
}
.confirm-dialog {
  background: #fff;
  padding: 28px;
  border-radius: 14px;
  width: min(460px, 100%);
  box-shadow: 0 25px 80px #14302026;
}
.confirm-dialog h2 {
  font-size: 20px;
  margin: 0 0 12px;
}
.confirm-dialog p {
  color: #8b9c90;
  font-size: 13px;
  line-height: 1.8;
}
.confirm-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 28px;
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
