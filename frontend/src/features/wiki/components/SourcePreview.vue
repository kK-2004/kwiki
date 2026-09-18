<template>
  <div class="source-preview" data-testid="source-preview">
    <div v-if="status === 'error'" class="source-state source-error" data-testid="source-error" role="alert">
      <i class="i-lucide-file-warning" aria-hidden="true"></i>
      <span>{{ errorText }}</span>
      <button v-if="retryable" type="button" data-testid="source-retry" @click="load">重试</button>
    </div>
    <template v-else>
      <p v-if="source.format === 'DOCX'" class="source-meta">{{ source.fileName }} · {{ sizeLabel }}</p>
      <div class="source-shell">
        <PdfSourceViewer
          v-if="source.format === 'PDF' && pdfInput"
          :key="pdfInput.key"
          :source="pdfInput.source"
          :file-name="source.fileName"
          :byte-size="source.byteSize"
          :http-headers="pdfInput.httpHeaders"
          @ready="onPdfReady"
          @error="onPdfError"
        />
        <div v-else-if="source.format === 'DOCX'" class="source-scroll">
          <div ref="renderer" class="source-renderer" :class="`fmt-${source.format.toLowerCase()}`"></div>
        </div>
        <div v-if="status === 'loading'" class="source-loading" data-testid="source-loading" role="status">正在加载源文件…</div>
        <div v-if="status === 'ready'" class="source-watermark" data-testid="source-watermark" aria-hidden="true">
          <div class="wm-grid">
            <span v-for="tile in 18" :key="tile">{{ watermarkText }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { SourceDocumentSummary } from '../api';
import { getAuthToken } from '../api';
import { useAuthStore } from '../../auth/store';
import { renderSource, SOURCE_PREVIEW_MAX_BYTES, type SourceRenderHandle } from './sourceAdapters';
import PdfSourceViewer from './PdfSourceViewer.vue';

const props = defineProps<{ kbId: number; pageId: number; source: SourceDocumentSummary }>();

const auth = useAuthStore();
const status = ref<'loading' | 'ready' | 'error'>('loading');
const errorText = ref('');
const retryable = ref(true);
const renderer = ref<HTMLElement | null>(null);
const pdfInput = ref<{ key: number; source: Blob | string; httpHeaders?: Record<string, string> } | null>(null);

let controller: AbortController | null = null;
let handle: SourceRenderHandle | null = null;
let generation = 0;
let mounted = false;
let pdfKey = 0;
let pdfAttempt: { key: number; resolve: () => void; reject: (error: unknown) => void; settled: boolean } | null = null;

/** 网络读取与浏览器解析共享同一个截止时间，避免 worker 异常时永久停在 loading。 */
const SOURCE_PREVIEW_TIMEOUT_MS = 30_000;

function previewTimeout(controllerToAbort: AbortController) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const promise = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      const error = new Error('源文件预览加载超时');
      error.name = 'SourcePreviewTimeoutError';
      reject(error);
      controllerToAbort.abort();
    }, SOURCE_PREVIEW_TIMEOUT_MS);
  });
  return {
    waitFor<T>(operation: Promise<T>): Promise<T> {
      return Promise.race([operation, promise]);
    },
    clear() {
      if (timer !== undefined) clearTimeout(timer);
    },
  };
}

const sizeLabel = computed(() => (props.source.byteSize >= 1024 * 1024
  ? `${(props.source.byteSize / 1024 / 1024).toFixed(1)} MB`
  : `${Math.max(1, Math.round(props.source.byteSize / 1024))} KB`));

/** 泄漏溯源水印：当前用户身份 + 打开时间，仅作提醒而非 DRM。 */
const watermarkText = computed(() => {
  const user = auth.user;
  const identity = user?.displayName && user.displayName !== user.username
    ? `${user.displayName} ${user.username}`
    : user?.username || '已登录用户';
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${identity} · ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
});

function release() {
  controller?.abort();
  controller = null;
  handle?.destroy();
  handle = null;
  if (pdfAttempt && !pdfAttempt.settled) {
    pdfAttempt.settled = true;
    pdfAttempt.reject(new DOMException('预览已取消', 'AbortError'));
  }
  pdfAttempt = null;
  pdfInput.value = null;
}

async function renderPdf(source: Blob | string, httpHeaders?: Record<string, string>) {
  const key = ++pdfKey;
  pdfInput.value = null;
  await nextTick();
  const promise = new Promise<void>((resolve, reject) => {
    pdfAttempt = { key, resolve, reject, settled: false };
  });
  pdfInput.value = { key, source, httpHeaders };
  return promise;
}

function onPdfReady() {
  if (!pdfAttempt || pdfAttempt.key !== pdfInput.value?.key || pdfAttempt.settled) return;
  pdfAttempt.settled = true;
  pdfAttempt.resolve();
}

function onPdfError(error: unknown) {
  if (!pdfAttempt || pdfAttempt.key !== pdfInput.value?.key || pdfAttempt.settled) return;
  pdfAttempt.settled = true;
  pdfAttempt.reject(error);
}

async function load() {
  const run = ++generation;
  release();
  status.value = 'loading';
  errorText.value = '';
  controller = new AbortController();
  const { signal } = controller;
  const deadline = previewTimeout(controller);
  try {
    const response = await deadline.waitFor(fetch(
      `/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/source-preview`,
      { headers: getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}, signal },
    ));
    if (run !== generation) return;
    if (!response.ok) {
      retryable.value = response.status >= 500;
      errorText.value = response.status === 403 || response.status === 404
        ? '无权访问该源文件，或源文件已失效'
        : '源文件预览加载失败';
      status.value = 'error';
      return;
    }
    const contentType = response.headers?.get('Content-Type') ?? '';
    let sourceData: Blob | string;
    if (contentType.includes('application/json')) {
      const payload = await deadline.waitFor(response.json()) as { data?: { url?: string } };
      const sourceUrl = payload.data?.url;
      if (!sourceUrl) throw new Error('源文件预览地址无效');
      // PDF.js 直接消费短期 URL，通过 Range 请求按需读取；不得携带 kwiki Authorization。
      if (props.source.format === 'PDF') sourceData = sourceUrl;
      else {
        const sourceResponse = await deadline.waitFor(fetch(sourceUrl, { signal }));
        if (!sourceResponse.ok) throw new Error('源文件下载失败');
        sourceData = await deadline.waitFor(sourceResponse.blob());
      }
    } else {
      // 兼容滚动升级期间仍返回字节的旧服务端。
      sourceData = await deadline.waitFor(response.blob());
    }
    if (run !== generation) return;
    if (sourceData instanceof Blob && sourceData.size > SOURCE_PREVIEW_MAX_BYTES) {
      retryable.value = false;
      errorText.value = '文件过大，无法在浏览器中预览';
      status.value = 'error';
      return;
    }
    // 渲染容器随首个渲染周期即已挂载；等待一次 tick 确保 DOM 就绪。
    await nextTick();
    if (run !== generation) return;
    if (props.source.format === 'PDF') {
      try {
        await deadline.waitFor(renderPdf(sourceData));
      } catch (error) {
        // 仅当直连 PDF 的跨域/地址不可用时，退回同源 Range 代理。
        if (typeof sourceData !== 'string' || signal.aborted) throw error;
        await deadline.waitFor(renderPdf(
          `/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/source-preview/content`,
          getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : undefined,
        ));
      }
    } else {
      if (!renderer.value) throw new Error('源文件预览容器未就绪');
      handle = await deadline.waitFor(renderSource(renderer.value, 'DOCX', sourceData, { signal }));
    }
    if (run !== generation) {
      handle?.destroy();
      handle = null;
      return;
    }
    status.value = 'ready';
  } catch (error) {
    if (run !== generation || (error as { name?: string })?.name === 'AbortError') return;
    pdfInput.value = null;
    retryable.value = true;
    const message = (error as Error)?.message ?? '';
    errorText.value = message.includes('过大')
      ? '文件过大，无法在浏览器中预览'
      : message.includes('超时')
        ? '源文件预览加载超时，请重试'
        : '源文件预览加载失败，请重试';
    status.value = 'error';
  } finally {
    deadline.clear();
  }
}

watch(() => [props.kbId, props.pageId, props.source.attachmentUuid], () => {
  // setup 阶段的 immediate watcher 可能早于模板 ref 挂载；仅在挂载完成后加载。
  if (mounted) void load();
});
onMounted(() => {
  mounted = true;
  void load();
});
onBeforeUnmount(() => {
  mounted = false;
  release();
});
</script>

<style scoped>
.source-preview{min-height:0;display:flex;flex-direction:column;gap:8px}
.source-loading{position:absolute;z-index:5;top:74px;left:50%;transform:translateX(-50%);padding:10px 16px;border-radius:8px;background:#eef4eff2;color:#7d8c81;font-size:13px;box-shadow:0 2px 10px #1f3c2814}
.source-error{display:flex;align-items:center;gap:8px;margin:24px auto;padding:14px 18px;border:1px solid #eed3b0;border-radius:10px;background:#fffaf2;color:#8a6427;font-size:13px}
.source-error i{font-size:17px}
.source-error button{border:1px solid #e0cba7;border-radius:7px;padding:5px 12px;background:#fff;color:#8a6427;font:inherit;font-size:12px;cursor:pointer}
.source-meta{margin:0;color:#8d9891;font-size:12px}
.source-shell{position:relative;flex:1;min-height:320px;border:1px solid #e2e8e3;border-radius:10px;background:#f7faf8;overflow:hidden}
.source-scroll{position:absolute;inset:0;overflow:auto;scrollbar-width:thin;overflow-anchor:none}
.source-renderer{padding:14px}
.source-renderer :deep(.kwiki-docx-wrapper){background:#fff;padding:8px 0}
.source-watermark{position:absolute;inset:0;pointer-events:none;overflow:hidden;display:grid;place-items:center}
.wm-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:64px 48px;transform:rotate(-22deg) scale(1.25);color:#2d49372e;user-select:none;font-size:12px;white-space:nowrap;text-align:center}
@media print{.source-watermark{position:fixed;inset:0;z-index:9999}.source-scroll{overflow:visible}}
</style>
