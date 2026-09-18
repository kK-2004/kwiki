<template>
  <div ref="rootEl" class="pdf-shell" data-testid="pdf-viewer">
    <header class="pdf-topbar">
      <div class="doc-meta">
        <div class="pdf-badge">PDF</div>
        <div class="doc-title-wrap">
          <div class="doc-title" :title="fileName">{{ fileName }}</div>
          <div class="doc-sub">{{ pageCount }} 页 · {{ sizeLabel }}</div>
        </div>
      </div>

      <div class="toolbar" aria-label="PDF 工具栏">
        <el-tooltip content="上一页" placement="bottom">
          <el-button class="tool-btn" text aria-label="上一页" :disabled="currentPage <= 1" @click="goPage(currentPage - 1)">‹</el-button>
        </el-tooltip>
        <div class="page-jump">
          <el-input v-model="pageInput" class="page-input" size="small" aria-label="页码" @keyup.enter="commitPage" @blur="commitPage" />
          <span>/ {{ pageCount }}</span>
        </div>
        <el-tooltip content="下一页" placement="bottom">
          <el-button class="tool-btn" text aria-label="下一页" :disabled="currentPage >= pageCount" @click="goPage(currentPage + 1)">›</el-button>
        </el-tooltip>
        <span class="divider" aria-hidden="true"></span>
        <el-tooltip content="缩小" placement="bottom">
          <el-button class="tool-btn" text aria-label="缩小" @click="zoomOut">−</el-button>
        </el-tooltip>
        <el-select v-model="zoomPercent" class="zoom-select" size="small" aria-label="缩放比例" @change="renderCurrentPage">
          <el-option v-for="zoom in zoomOptions" :key="zoom" :label="`${zoom}%`" :value="zoom" />
        </el-select>
        <el-tooltip content="放大" placement="bottom">
          <el-button class="tool-btn" text aria-label="放大" @click="zoomIn">＋</el-button>
        </el-tooltip>
        <span class="divider" aria-hidden="true"></span>
        <el-button class="tool-btn fit-button" text @click="fitWidth">适宽</el-button>
        <el-tooltip content="顺时针旋转" placement="bottom">
          <el-button class="tool-btn" text aria-label="顺时针旋转" @click="rotate">↻</el-button>
        </el-tooltip>
      </div>

      <div class="actions">
        <el-button class="hide-sm" size="small" text @click="printPdf">打印</el-button>
        <el-button class="hide-sm" size="small" text @click="downloadPdf">下载</el-button>
        <el-button size="small" text @click="toggleFullscreen">全屏</el-button>
      </div>
    </header>

    <main class="pdf-main">
      <aside class="sidebar">
        <div class="sidebar-head">
          <span class="sidebar-title">页面缩略图</span>
          <span class="page-total">{{ pageCount }} 页</span>
        </div>
        <div ref="thumbsEl" class="thumbs">
          <button
            v-for="pageNumber in pageCount"
            :key="pageNumber"
            :ref="element => setThumbItem(element, pageNumber)"
            type="button"
            class="thumb-item"
            :class="{ active: pageNumber === currentPage }"
            :aria-label="`第 ${pageNumber} 页`"
            :aria-current="pageNumber === currentPage ? 'page' : undefined"
            @click="goPage(pageNumber)"
          >
            <span class="thumb-page">
              <canvas :ref="element => setThumbCanvas(element, pageNumber)"></canvas>
              <span v-if="!renderedThumbs.has(pageNumber)" class="thumb-placeholder" aria-hidden="true">
                <i></i><i></i><i></i><i></i>
              </span>
            </span>
            <span class="thumb-index">{{ pageNumber }}</span>
          </button>
        </div>
      </aside>

      <section ref="viewerEl" class="viewer" @wheel="onWheel">
        <div class="viewer-inner">
          <div class="paper" :style="paperStyle">
            <canvas ref="canvasEl" class="source-pdf-page" data-testid="pdf-page-canvas"></canvas>
          </div>
        </div>
        <div v-if="rendering" class="rendering-hint" role="status">正在渲染第 {{ currentPage }} 页…</div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import 'element-plus/es/components/button/style/css';
import 'element-plus/es/components/input/style/css';
import 'element-plus/es/components/select/style/css';
import 'element-plus/es/components/tooltip/style/css';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElButton, ElInput, ElOption, ElSelect, ElTooltip } from 'element-plus';
import type { ComponentPublicInstance, CSSProperties } from 'vue';
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from 'pdfjs-dist';

const props = defineProps<{
  source: Blob | string;
  fileName: string;
  byteSize: number;
  httpHeaders?: Record<string, string>;
}>();

const emit = defineEmits<{
  ready: [];
  error: [error: unknown];
}>();

const rootEl = ref<HTMLElement | null>(null);
const viewerEl = ref<HTMLElement | null>(null);
const thumbsEl = ref<HTMLElement | null>(null);
const canvasEl = ref<HTMLCanvasElement | null>(null);
const pageCount = ref(0);
const currentPage = ref(1);
const pageInput = ref('1');
const zoomPercent = ref(100);
const rotation = ref(0);
const canvasCssWidth = ref(0);
const canvasCssHeight = ref(0);
const rendering = ref(false);
const renderedThumbs = ref(new Set<number>());
const zoomOptions = [50, 67, 75, 90, 100, 110, 125, 150, 175, 200];

let pdfDoc: PDFDocumentProxy | null = null;
let loadingTask: { promise: Promise<PDFDocumentProxy>; destroy(): Promise<void> } | null = null;
let renderTask: RenderTask | null = null;
let thumbnailObserver: IntersectionObserver | null = null;
let destroyed = false;
let renderSequence = 0;
const thumbCanvases = new Map<number, HTMLCanvasElement>();
const thumbItems = new Map<number, HTMLElement>();

const sizeLabel = computed(() => (props.byteSize >= 1024 * 1024
  ? `${(props.byteSize / 1024 / 1024).toFixed(1)} MB`
  : `${Math.max(1, Math.round(props.byteSize / 1024))} KB`));
const paperStyle = computed<CSSProperties>(() => ({
  width: `${canvasCssWidth.value}px`,
  minHeight: `${canvasCssHeight.value}px`,
}));

function asElement(element: Element | ComponentPublicInstance | null): HTMLElement | null {
  return element instanceof HTMLElement ? element : null;
}

function setThumbCanvas(element: Element | ComponentPublicInstance | null, pageNumber: number) {
  if (element instanceof HTMLCanvasElement) thumbCanvases.set(pageNumber, element);
  else thumbCanvases.delete(pageNumber);
}

function setThumbItem(element: Element | ComponentPublicInstance | null, pageNumber: number) {
  const htmlElement = asElement(element);
  if (htmlElement) {
    htmlElement.dataset.pageNumber = String(pageNumber);
    thumbItems.set(pageNumber, htmlElement);
  } else thumbItems.delete(pageNumber);
}

async function loadPdf() {
  try {
    const [pdfjs, worker] = await Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
    ]);
    if (destroyed) return;
    pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
    loadingTask = (typeof props.source === 'string'
      ? pdfjs.getDocument({
          url: props.source,
          isEvalSupported: false,
          disableStream: true,
          disableAutoFetch: true,
          rangeChunkSize: 256 * 1024,
          httpHeaders: props.httpHeaders,
        })
      : pdfjs.getDocument({
          data: new Uint8Array(await props.source.arrayBuffer()),
          isEvalSupported: false,
        })) as typeof loadingTask;
    if (!loadingTask) throw new Error('PDF 加载任务创建失败');
    pdfDoc = await loadingTask.promise;
    if (destroyed) return;
    pageCount.value = pdfDoc.numPages;
    currentPage.value = 1;
    pageInput.value = '1';
    await nextTick();
    setupThumbnailObserver();
    await renderCurrentPage();
    if (!destroyed) emit('ready');
  } catch (error) {
    if (!destroyed && (error as { name?: string }).name !== 'RenderingCancelledException') emit('error', error);
  }
}

async function renderPageToCanvas(page: PDFPageProxy, canvas: HTMLCanvasElement, cssScale: number, renderRotation: number, dpr: number) {
  const viewport = page.getViewport({ scale: cssScale, rotation: renderRotation });
  const context = canvas.getContext('2d', { alpha: false });
  if (!context) throw new Error('浏览器不支持 Canvas PDF 渲染');
  canvas.width = Math.max(1, Math.floor(viewport.width * dpr));
  canvas.height = Math.max(1, Math.floor(viewport.height * dpr));
  canvas.style.width = `${viewport.width}px`;
  canvas.style.height = `${viewport.height}px`;
  context.setTransform(1, 0, 0, 1, 0, 0);
  const task = page.render({
    canvasContext: context,
    viewport,
    transform: dpr === 1 ? undefined : [dpr, 0, 0, dpr, 0, 0],
  });
  await task.promise;
  return viewport;
}

async function renderCurrentPage() {
  if (!pdfDoc || !canvasEl.value) return;
  const sequence = ++renderSequence;
  rendering.value = true;
  renderTask?.cancel();
  try {
    const page = await pdfDoc.getPage(currentPage.value);
    if (sequence !== renderSequence || destroyed || !canvasEl.value) return;
    const scale = (96 / 72) * (zoomPercent.value / 100);
    const viewport = page.getViewport({ scale, rotation: rotation.value });
    const canvas = canvasEl.value;
    const context = canvas.getContext('2d', { alpha: false });
    if (!context) throw new Error('浏览器不支持 Canvas PDF 渲染');
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.floor(viewport.width * dpr));
    canvas.height = Math.max(1, Math.floor(viewport.height * dpr));
    canvas.style.width = `${viewport.width}px`;
    canvas.style.height = `${viewport.height}px`;
    canvasCssWidth.value = viewport.width;
    canvasCssHeight.value = viewport.height;
    context.setTransform(1, 0, 0, 1, 0, 0);
    renderTask = page.render({
      canvasContext: context,
      viewport,
      transform: dpr === 1 ? undefined : [dpr, 0, 0, dpr, 0, 0],
    });
    await renderTask.promise;
  } catch (error) {
    if ((error as { name?: string }).name !== 'RenderingCancelledException' && !destroyed) emit('error', error);
  } finally {
    if (sequence === renderSequence) rendering.value = false;
  }
}

async function renderThumbnail(pageNumber: number) {
  if (!pdfDoc || renderedThumbs.value.has(pageNumber)) return;
  const canvas = thumbCanvases.get(pageNumber);
  if (!canvas) return;
  try {
    const page = await pdfDoc.getPage(pageNumber);
    if (destroyed) return;
    const baseViewport = page.getViewport({ scale: 1 });
    await renderPageToCanvas(page, canvas, 112 / baseViewport.width, 0, Math.min(window.devicePixelRatio || 1, 1.5));
    if (!destroyed) {
      renderedThumbs.value = new Set(renderedThumbs.value).add(pageNumber);
      const item = thumbItems.get(pageNumber);
      if (item) thumbnailObserver?.unobserve(item);
    }
  } catch (error) {
    if ((error as { name?: string }).name !== 'RenderingCancelledException' && !destroyed) console.warn('PDF 缩略图渲染失败', error);
  }
}

function setupThumbnailObserver() {
  thumbnailObserver?.disconnect();
  if (!thumbsEl.value || typeof IntersectionObserver === 'undefined') {
    void renderThumbnail(1);
    return;
  }
  thumbnailObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const pageNumber = Number((entry.target as HTMLElement).dataset.pageNumber);
      if (pageNumber) void renderThumbnail(pageNumber);
    }
  }, { root: thumbsEl.value, rootMargin: '160px 0px' });
  thumbItems.forEach(item => thumbnailObserver?.observe(item));
}

async function goPage(pageNumber: number) {
  if (!pageCount.value) return;
  const nextPage = Math.max(1, Math.min(Math.trunc(Number(pageNumber) || 1), pageCount.value));
  currentPage.value = nextPage;
  pageInput.value = String(nextPage);
  viewerEl.value?.scrollTo({ top: 0, left: 0 });
  thumbItems.get(nextPage)?.scrollIntoView({ block: 'nearest' });
  await renderCurrentPage();
}

function commitPage() { void goPage(Number(pageInput.value)); }
function zoomIn() { zoomPercent.value = Math.min(200, zoomPercent.value + 10); void renderCurrentPage(); }
function zoomOut() { zoomPercent.value = Math.max(40, zoomPercent.value - 10); void renderCurrentPage(); }

async function fitWidth() {
  if (!pdfDoc || !viewerEl.value) return;
  const page = await pdfDoc.getPage(currentPage.value);
  const baseViewport = page.getViewport({ scale: 96 / 72, rotation: rotation.value });
  const availableWidth = Math.max(240, viewerEl.value.clientWidth - 72);
  zoomPercent.value = Math.max(40, Math.min(200, Math.floor((availableWidth / baseViewport.width) * 100)));
  await renderCurrentPage();
}

function rotate() { rotation.value = (rotation.value + 90) % 360; void renderCurrentPage(); }
function onWheel(event: WheelEvent) {
  if (!(event.ctrlKey || event.metaKey)) return;
  event.preventDefault();
  if (event.deltaY < 0) zoomIn(); else zoomOut();
}

async function sourceUrl(forceBlob = false): Promise<{ url: string; temporary: boolean }> {
  if (props.source instanceof Blob) return { url: URL.createObjectURL(props.source), temporary: true };
  // 同源 Range 代理依赖 Bearer 头，只有用户主动下载/打印时才读取完整文件。
  if (forceBlob || (props.httpHeaders && Object.keys(props.httpHeaders).length > 0)) {
    const response = await fetch(props.source, { headers: props.httpHeaders });
    if (!response.ok) throw new Error('PDF 文件读取失败');
    return { url: URL.createObjectURL(await response.blob()), temporary: true };
  }
  return { url: props.source, temporary: false };
}

async function downloadPdf() {
  try {
    const { url, temporary } = await sourceUrl(true);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = props.fileName || 'document.pdf';
    anchor.rel = 'noopener';
    anchor.click();
    if (temporary) setTimeout(() => URL.revokeObjectURL(url), 0);
  } catch (error) {
    console.warn('PDF 下载失败', error);
  }
}

async function printPdf() {
  const printWindow = window.open('', '_blank');
  if (!printWindow) return;
  try {
    const { url, temporary } = await sourceUrl(true);
    printWindow.location.href = url;
    setTimeout(() => printWindow.print(), 500);
    if (temporary) setTimeout(() => URL.revokeObjectURL(url), 30_000);
  } catch (error) {
    printWindow.close();
    console.warn('PDF 打印失败', error);
  }
}

async function toggleFullscreen() {
  if (!document.fullscreenElement) await rootEl.value?.requestFullscreen?.();
  else await document.exitFullscreen?.();
}

onMounted(() => { void loadPdf(); });
onBeforeUnmount(() => {
  destroyed = true;
  renderSequence += 1;
  renderTask?.cancel();
  thumbnailObserver?.disconnect();
  void loadingTask?.destroy();
  void pdfDoc?.destroy();
});
</script>

<style scoped>
*{box-sizing:border-box}
.pdf-shell{--header-h:58px;--sidebar-w:208px;--line:#e5e6eb;--muted:#86909c;--viewer:#eef0f3;height:100%;min-height:520px;display:flex;flex-direction:column;background:#fff;color:#1f2329}
.pdf-topbar{height:var(--header-h);flex:0 0 var(--header-h);padding:0 14px 0 18px;display:grid;grid-template-columns:minmax(180px,1fr) auto minmax(180px,1fr);align-items:center;gap:14px;border-bottom:1px solid var(--line);background:#fffffff5;z-index:3}
.doc-meta{min-width:0;display:flex;align-items:center;gap:11px}.pdf-badge{width:34px;height:34px;border:1px solid #ffe1df;border-radius:8px;display:grid;place-items:center;flex:0 0 auto;background:#fff1f0;color:#f53f3f;font-size:11px;font-weight:800}.doc-title-wrap{min-width:0}.doc-title{max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;font-weight:600}.doc-sub{margin-top:2px;color:var(--muted);font-size:11px}
.toolbar,.actions{display:flex;align-items:center;gap:6px;white-space:nowrap}.toolbar{justify-content:center}.actions{justify-content:flex-end}.toolbar :deep(.el-button+.el-button),.actions :deep(.el-button+.el-button){margin-left:0}.tool-btn{min-width:32px!important;padding:7px 9px!important;border-color:transparent!important;background:transparent!important;color:#4e5969!important}.tool-btn:hover{background:#f2f3f5!important;color:#1d2129!important}.divider{width:1px;height:18px;margin:0 3px;background:var(--line)}.page-jump{display:flex;align-items:center;gap:6px;color:#4e5969;font-size:13px}.page-input{width:54px}.page-input :deep(.el-input__wrapper){padding:0 7px}.page-input :deep(input){text-align:center}.zoom-select{width:98px}
.pdf-main{min-height:0;flex:1;display:flex}.sidebar{width:var(--sidebar-w);flex:0 0 var(--sidebar-w);min-height:0;display:flex;flex-direction:column;border-right:1px solid var(--line);background:#f7f8fa}.sidebar-head{height:45px;flex:0 0 45px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid #eceef0}.sidebar-title{color:#4e5969;font-size:12px;font-weight:600}.page-total{color:#a2a9b2;font-size:11px}.thumbs{min-height:0;flex:1;overflow:auto;padding:12px 12px 20px}.thumb-item{width:100%;display:flex;align-items:flex-start;gap:8px;margin:0 0 12px;padding:7px;border:1px solid transparent;border-radius:8px;background:transparent;text-align:left;cursor:pointer;transition:.15s ease}.thumb-item:hover{background:#eef0f3}.thumb-item.active{border-color:#bedaff;background:#e8f3ff}.thumb-page{position:relative;width:112px;aspect-ratio:.707;overflow:hidden;display:flex;align-items:center;justify-content:center;border:1px solid #dfe1e5;border-radius:3px;background:#fff;box-shadow:0 2px 8px #0000000a}.thumb-page canvas{display:block;max-width:100%;max-height:100%}.thumb-placeholder{position:absolute;width:78%;height:76%;display:flex;flex-direction:column;gap:7px;padding-top:14px}.thumb-placeholder i{height:4px;border-radius:3px;background:#e8eaed}.thumb-placeholder i:nth-child(2){width:76%}.thumb-placeholder i:nth-child(3){width:90%}.thumb-placeholder i:nth-child(4){width:62%}.thumb-index{min-width:18px;padding-top:3px;color:#86909c;font-size:11px;text-align:center}
.viewer{position:relative;min-width:0;min-height:0;flex:1;overflow:auto;background:var(--viewer)}.viewer-inner{min-width:100%;min-height:100%;padding:34px 36px 54px;display:flex;align-items:flex-start;justify-content:center}.paper{overflow:hidden;border:1px solid #e1e3e6;background:#fff;box-shadow:0 8px 28px #1c1f231f}.source-pdf-page{display:block;margin:0 auto;background:#fff}.rendering-hint{position:sticky;bottom:16px;width:max-content;margin:0 auto 16px;padding:7px 12px;border-radius:7px;background:#1f2329d1;color:#fff;font-size:12px;pointer-events:none}
.pdf-shell:fullscreen{min-height:100vh}.pdf-shell:fullscreen .pdf-main{height:calc(100vh - var(--header-h))}
@media(max-width:1100px){.pdf-topbar{grid-template-columns:minmax(160px,1fr) auto}.toolbar{display:none}.sidebar{width:160px;flex-basis:160px}.thumb-page{width:82px}.doc-title{max-width:260px}.actions .hide-sm{display:none}}
@media(max-width:680px){.sidebar{display:none}.pdf-topbar{padding-left:10px}.doc-title{max-width:180px}.viewer-inner{padding:20px 16px 36px}}
</style>
