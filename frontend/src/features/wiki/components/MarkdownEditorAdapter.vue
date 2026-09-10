<template>
  <div ref="editorRoot" class="block-editor" data-testid="markdown-editor" @pointerdown.capture="focusEditor" @mousedown.capture="focusEditor" @click.capture="focusEditor">
    <div class="segments" data-testid="markdown-editor-source">
      <template v-for="(row, index) in rows" :key="row.key">
        <div v-if="row.kind === 'text' && row.segment?.type === 'text'" class="text-segment">
          <textarea
            v-auto-grow
            :value="row.segment.text"
            rows="1"
            :aria-label="`正文段落 ${index + 1}`"
            @input="autoGrow($event); editTextSegment(row.segment!, ($event.target as HTMLTextAreaElement).value)"
            @focus="trackFocus(row.textIndex!, $event)"
            @keyup="trackFocus(row.textIndex!, $event)"
            @click="trackFocus(row.textIndex!, $event)"
            @keydown="onKeydown"
          ></textarea>
        </div>
        <div
          v-else-if="row.kind === 'media' && row.segment?.type === 'media'"
          class="media-segment"
          tabindex="0"
          @focusin="activeMedia = row.key"
          @focusout="onMediaFocusOut(row.key, $event)"
          @mouseover="hoverMedia = row.key"
          @mouseleave="hoverMedia = null"
          @click="activeMedia = row.key"
        >
          <!-- Tools live in the segment's stable container, not inside the
               async viewer, so delete/layout stay reachable even while the
               viewer chunk or media is still loading. -->
          <MediaBlock :media="row.segment.media" :resolver="mediaResolver" :kb-id="props.kbId" />
          <div v-if="(activeMedia === row.key || hoverMedia === row.key) && row.segment.media.kind !== 'ATTACHMENT'" class="media-controls" data-testid="media-controls">
              <label>
                布局
                <select
                  :value="row.segment.media.align || (row.segment.media.kind === 'IMAGE' ? 'center' : 'left')"
                  aria-label="媒体布局"
                  @change="updateMedia(row.segment!, { align: ($event.target as HTMLSelectElement).value as MediaAttrs['align'] })"
                >
                  <option value="left">左</option>
                  <option value="center">中</option>
                  <option value="right">右</option>
                </select>
              </label>
              <label>
                尺寸
                <select
                  :value="sizeValue(row.segment.media)"
                  aria-label="媒体尺寸"
                  @change="onSizeChange(row.segment!, ($event.target as HTMLSelectElement).value)"
                >
                  <option value="auto">自动</option>
                  <option value="25">25%</option>
                  <option value="50">50%</option>
                  <option value="75">75%</option>
                  <option value="100">100%</option>
                  <option value="px">固定宽度…</option>
                </select>
              </label>
              <button type="button" class="remove-media" aria-label="删除媒体" title="删除媒体" @click.stop="removeMedia(row.segment)">
                <i class="i-lucide-trash-2" aria-hidden="true"></i>
              </button>
          </div>
        </div>
        <div v-else-if="row.kind === 'upload' && row.view" class="upload-segment">
          <pre class="upload-text">{{ row.view.before }}</pre>
          <div class="upload-placeholder" :class="{ failed: row.upload?.status === 'failed' }" data-testid="upload-placeholder">
            <template v-if="row.upload?.status === 'uploading'">
              <span class="spinner" aria-hidden="true"></span> 正在上传 {{ row.upload.name }}…
            </template>
            <template v-else>
              <span>{{ row.upload?.name }} 上传失败：{{ row.upload?.error }}</span>
              <button type="button" @click="retryUpload(row.upload!.id)">重试</button>
              <button type="button" @click="removeUpload(row.upload!.id)">移除</button>
            </template>
          </div>
          <pre class="upload-text">{{ row.view.after }}</pre>
        </div>
      </template>
    </div>
    <div v-if="failedUploads.length" class="upload-errors" role="alert">
      存在上传失败的媒体，可重试或移除后再发布。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Block editing view over the markdown source. The source is split by
 * scanMediaBlocks into text and media segments with exact offsets: text
 * segments are native textareas (native input/undo inside a segment), media
 * segments render real previews with hover/focus layout+size controls, and
 * every edit splices only the range it touched — untouched text round-trips
 * byte for byte. The mapped source view is paired with the rendered preview;
 * there is no separate legacy editor mode.
 * Uploads insert an in-view placeholder that is never persisted into the
 * source; on success the stable attachment:// token replaces it at the
 * recorded cursor position. Unmounting cancels in-flight uploads.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  isHttpUrl, scanMediaBlocks, serializeMedia,
  type MediaAttrs, type MediaKind, type Segment,
} from './mediaBlocks';
import { createMediaPreviewResolver } from './mediaResolver';
import type { MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';
import MediaBlock from './MediaBlock.vue';
import { api, errorMessage } from '../api';

const props = defineProps<{
  modelValue: string;
  kbId?: number;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const source = ref(props.modelValue);
const undoStack: string[] = [];
const redoStack: string[] = [];

interface PendingUpload {
  id: number;
  name: string;
  byteSize: number;
  kind: MediaKind;
  status: 'uploading' | 'failed';
  error?: string;
  segmentIndex: number;
  offset: number;
  controller?: AbortController;
}
const pendingUploads = ref<PendingUpload[]>([]);
let uploadSeq = 0;
const editorRoot = ref<HTMLElement | null>(null);

watch(
  () => props.modelValue,
  (value) => {
    if (value !== source.value) source.value = value;
  },
);
watch(source, (value, old) => {
  if (value === old) return;
  if (old != null) {
    undoStack.push(old);
    if (undoStack.length > 100) undoStack.shift();
    redoStack.length = 0;
  }
  emit('update:modelValue', value);
});

const segments = computed<Segment[]>(() => scanMediaBlocks(source.value));

interface Row {
  kind: 'text' | 'media' | 'upload';
  key: string;
  textIndex?: number;
  segment?: Segment;
  upload?: PendingUpload;
  view?: { before: string; after: string };
}
const rows = computed<Row[]>(() => {
  const result: Row[] = [];
  const textIndexByOrdinal = new Map<number, number>();
  let textOrdinal = 0;
  for (const segment of segments.value) {
    if (segment.type === 'text') {
      textIndexByOrdinal.set(textOrdinal, result.length);
      result.push({ kind: 'text', key: `t${textOrdinal}`, textIndex: textOrdinal, segment });
      textOrdinal += 1;
    } else {
      result.push({ kind: 'media', key: `m${segment.start}-${segment.end}`, segment });
    }
  }
  if (result.length === 0) {
    // An empty document still needs one editable text segment.
    result.push({ kind: 'text', key: 't0', textIndex: 0, segment: { type: 'text', start: 0, end: 0, text: '' } });
  } else if (segments.value.at(-1)?.type === 'media') {
    // Keep a trailing insertion point after the last media block so the whole
    // editor surface remains editable, including a media-only document.
    result.push({ kind: 'text', key: `t${textOrdinal}`, textIndex: textOrdinal, segment: { type: 'text', start: source.value.length, end: source.value.length, text: '' } });
  }
  for (const upload of pendingUploads.value) {
    const targetIndex = textIndexByOrdinal.get(upload.segmentIndex);
    if (targetIndex == null) continue;
    const textSegment = result[targetIndex].segment as Extract<Segment, { type: 'text' }> | undefined;
    if (!textSegment) continue;
    const offset = Math.min(upload.offset, textSegment.text.length);
    result.splice(targetIndex, 1, {
      kind: 'upload',
      key: `u${upload.id}`,
      upload,
      view: { before: textSegment.text.slice(0, offset), after: textSegment.text.slice(offset) },
    });
  }
  return result;
});

const failedUploads = computed(() => pendingUploads.value.filter(item => item.status === 'failed'));

const focused = ref<{ segmentIndex: number; offset: number }>({ segmentIndex: 0, offset: 0 });
const textareas = new Map<number, HTMLTextAreaElement>();

function resizeTextarea(textarea: HTMLTextAreaElement) {
  textarea.style.height = 'auto';
  textarea.style.height = `${textarea.scrollHeight}px`;
}

const vAutoGrow = {
  mounted: resizeTextarea,
  updated: resizeTextarea,
};

function autoGrow(event: Event) {
  resizeTextarea(event.target as HTMLTextAreaElement);
}

function trackFocus(index: number, event: Event) {
  const textarea = event.target as HTMLTextAreaElement;
  textareas.set(index, textarea);
  activeMedia.value = null;
  focused.value = { segmentIndex: index, offset: textarea.selectionStart ?? textarea.selectionEnd ?? 0 };
}

function editTextSegment(segment: Segment, next: string) {
  if (segment.type !== 'text') return;
  source.value = source.value.slice(0, segment.start) + next + source.value.slice(segment.end);
}

// ------------------------------------------------------------------
// media preview + layout/size controls
// ------------------------------------------------------------------
const activeMedia = ref<string | null>(null);
/** Hover shows the controls transiently; focus/click keeps them while the
 *  segment stays selected, so the dropdowns stay operable. */
const hoverMedia = ref<string | null>(null);
/** Hide the controls when focus leaves the segment entirely (click away,
 *  Tab into text); moving focus within — the viewer or the controls
 *  themselves — keeps them alive. */
function onMediaFocusOut(key: string, event: FocusEvent) {
  const next = event.relatedTarget as Node | null;
  const segment = event.currentTarget as HTMLElement | null;
  if (next && segment?.contains(next)) return;
  if (activeMedia.value === key) activeMedia.value = null;
}
/** One resolver per knowledge base shares preview-URL fetches across rows. */
const mediaResolver = ref<MediaSourceResolver>(() => null);
watch(
  () => props.kbId,
  kbId => { mediaResolver.value = createMediaPreviewResolver(kbId); },
  { immediate: true },
);

function focusEditor(event: MouseEvent) {
  const target = event.target as HTMLElement | null;
  if (target?.closest('textarea, button, select, a, .media-segment')) return;
  const editable = editorRoot.value?.querySelectorAll('textarea');
  const textarea = editable?.[editable.length - 1] as HTMLTextAreaElement | undefined;
  if (!textarea) return;
  // Prevent the browser's default mousedown focus on the outer block. Without
  // this, the textarea briefly receives focus and the click immediately moves
  // it back to the blank editor container.
  event.preventDefault();
  textarea.focus();
  const end = textarea.value.length;
  textarea.setSelectionRange(end, end);
}

function removeMedia(segment: Segment | undefined) {
  if (!segment || segment.type !== 'media') return;
  source.value = source.value.slice(0, segment.start) + source.value.slice(segment.end);
  activeMedia.value = null;
  hoverMedia.value = null;
}

function updateMedia(segment: Segment, patch: Partial<MediaAttrs>) {
  if (segment.type !== 'media') return;
  const next: MediaAttrs = { ...segment.media, ...patch };
  if (patch.widthPercent) next.widthPx = undefined;
  if (patch.widthPx) next.widthPercent = undefined;
  const serialized = serializeMedia(next);
  source.value = source.value.slice(0, segment.start) + serialized + source.value.slice(segment.end);
  // The segment key changes with its range; keep the controls alive so a
  // sequence of adjustments does not close them after every change.
  if (activeMedia.value) activeMedia.value = `m${segment.start}-${segment.start + serialized.length}`;
}

function sizeValue(media: MediaAttrs): string {
  if (media.widthPercent) return String(media.widthPercent);
  if (media.widthPx) return 'px';
  return 'auto';
}

function onSizeChange(segment: Segment, value: string) {
  if (segment.type !== 'media') return;
  if (value === 'auto') {
    updateMedia(segment, { widthPercent: undefined, widthPx: undefined });
  } else if (value === 'px') {
    const current = segment.media.widthPx ?? 640;
    const input = window.prompt('固定宽度（80–1920 像素）', String(current));
    if (input == null) return;
    const px = Number(input);
    if (Number.isFinite(px) && px >= 80 && px <= 1920) {
      updateMedia(segment, { widthPx: Math.round(px), widthPercent: undefined });
    }
  } else {
    updateMedia(segment, { widthPercent: Number(value), widthPx: undefined });
  }
}

// ------------------------------------------------------------------
// uploads
// ------------------------------------------------------------------
const ACCEPT: Record<MediaKind, string> = {
  IMAGE: 'image/png,image/jpeg,image/gif,image/webp',
  AUDIO: 'audio/mpeg,audio/mp3,audio/wav,audio/ogg',
  VIDEO: 'video/mp4,video/webm',
  ATTACHMENT: '*/*',
};
const fileInputs = new Map<MediaKind, HTMLInputElement>();

function pickFile(kind: MediaKind) {
  let input = fileInputs.get(kind);
  if (!input) {
    input = document.createElement('input');
    input.type = 'file';
    input.accept = ACCEPT[kind];
    input.addEventListener('change', () => {
      const file = input!.files?.[0];
      if (file) void uploadFile(kind, file);
      input!.value = '';
    });
    fileInputs.set(kind, input);
  }
  input.click();
}

type AttachmentUploadResult = { uuid: string; fileName: string; contentType: string; byteSize: number };

async function uploadFile(kind: MediaKind, file: File) {
  if (!props.kbId) return;
  const id = ++uploadSeq;
  const controller = new AbortController();
  const upload: PendingUpload = {
    id,
    name: file.name,
    byteSize: file.size,
    kind,
    status: 'uploading',
    segmentIndex: focused.value.segmentIndex,
    offset: focused.value.offset,
    controller,
  };
  pendingUploads.value = [...pendingUploads.value, upload];
  const form = new FormData();
  form.append('file', file);
  try {
    const result = await api.upload<AttachmentUploadResult>(
      `/knowledge-bases/${props.kbId}/attachments`, form, controller.signal);
    insertMediaToken(kind, result.uuid, result.fileName || file.name, result.byteSize || file.size);
    removeUpload(id);
  } catch (error) {
    const target = pendingUploads.value.find(item => item.id === id);
    if (target && target.controller?.signal.aborted !== true) {
      target.status = 'failed';
      target.error = errorMessage(error, '上传失败');
      pendingUploads.value = [...pendingUploads.value];
    } else {
      removeUpload(id);
    }
  }
}

/** A failed upload retries by picking the file again at the recorded position. */
function retryUpload(id: number) {
  const upload = pendingUploads.value.find(item => item.id === id);
  if (!upload) return;
  removeUpload(id);
  pickFile(upload.kind);
}

function removeUpload(id: number) {
  const upload = pendingUploads.value.find(item => item.id === id);
  upload?.controller?.abort();
  pendingUploads.value = pendingUploads.value.filter(item => item.id !== id);
}

function insertMediaToken(kind: MediaKind, uuid: string, name: string, byteSize: number) {
  const token = `attachment://${uuid}`;
  const syntax = kind === 'IMAGE'
    ? serializeMedia({ kind, src: token, alt: name.replace(/\.[^.]+$/, ''), align: 'center', widthPercent: 50 })
    : kind === 'ATTACHMENT'
      ? serializeMedia({ kind, src: token, fileName: name, byteSize })
      : serializeMedia({ kind, src: token });
  insertAtFocus(syntax);
}

function insertExternalLink(kind: MediaKind, url: string) {
  if (!isHttpUrl(url)) return;
  const syntax = kind === 'IMAGE'
    ? serializeMedia({ kind, src: url, align: 'center', widthPercent: 50 })
    : serializeMedia({ kind, src: url });
  insertAtFocus(syntax);
}

function insertAtFocus(text: string) {
  const textSegments = segments.value.filter(segment => segment.type === 'text') as Array<Extract<Segment, { type: 'text' }>>;
  const index = Math.min(focused.value.segmentIndex, Math.max(textSegments.length - 1, 0));
  const target = textSegments[index];
  if (!target) {
    source.value = source.value + text;
    return;
  }
  const offset = Math.min(focused.value.offset, target.text.length);
  const absolute = target.start + offset;
  source.value = source.value.slice(0, absolute) + text + source.value.slice(absolute);
  focused.value = { segmentIndex: index, offset: offset + text.length };
}

// ------------------------------------------------------------------
// toolbar commands (selection-based, shared contract with PageEditor)
// ------------------------------------------------------------------
type EditorCommand = 'h1' | 'h2' | 'bold' | 'code';

function applyCommand(command: EditorCommand, language = '') {
  const textarea = textareas.get(focused.value.segmentIndex);
  if (!textarea) return;
  applyToTextarea(textarea, command, language);
  const textSegments = segments.value.filter(segment => segment.type === 'text') as Array<Extract<Segment, { type: 'text' }>>;
  const segment = textSegments[focused.value.segmentIndex];
  if (segment) editTextSegment(segment, textarea.value);
}

function applyToTextarea(textarea: HTMLTextAreaElement | undefined | null, command: EditorCommand, language = '') {
  if (!textarea) return;
  const { selectionStart: start, selectionEnd: end, value } = textarea;
  const selected = value.slice(start, end);
  let next = value;
  let caret = end;
  switch (command) {
    case 'h1':
    case 'h2': {
      const prefix = command === 'h1' ? '# ' : '## ';
      const lineStart = value.lastIndexOf('\n', start - 1) + 1;
      next = value.slice(0, lineStart) + prefix + value.slice(lineStart);
      caret = end + prefix.length;
      break;
    }
    case 'bold': {
      const body = selected || '加粗文本';
      next = `${value.slice(0, start)}**${body}**${value.slice(end)}`;
      caret = start + 2 + body.length;
      break;
    }
    case 'code': {
      let run = 0;
      for (let i = 0; i < selected.length; i++) run = selected[i] === '`' ? run + 1 : 0;
      const fence = '`'.repeat(Math.max(3, run + 1));
      const body = selected;
      next = `${value.slice(0, start)}\n${fence}${language}\n${body}\n${fence}\n${value.slice(end)}`;
      caret = start + fence.length + language.length + 2 + body.length;
      break;
    }
  }
  textarea.value = next;
  textarea.focus();
  textarea.setSelectionRange(caret, caret);
  textarea.dispatchEvent(new Event('input', { bubbles: true }));
}

function undoEdit() {
  const previous = undoStack.pop();
  if (previous == null) return;
  redoStack.push(source.value);
  source.value = previous;
}

function redoEdit() {
  const next = redoStack.pop();
  if (next == null) return;
  undoStack.push(source.value);
  source.value = next;
}

function onKeydown(event: KeyboardEvent) {
  const meta = event.metaKey || event.ctrlKey;
  if (!meta) return;
  const key = event.key.toLowerCase();
  if (key === 'z' && !event.shiftKey) {
    event.preventDefault();
    undoEdit();
  } else if ((key === 'z' && event.shiftKey) || key === 'y') {
    event.preventDefault();
    redoEdit();
  }
}

onBeforeUnmount(() => {
  for (const upload of pendingUploads.value) upload.controller?.abort();
  pendingUploads.value = [];
});

defineExpose({
  applyCommand,
  pickFile,
  insertExternalLink,
  pendingUploadCount: computed(() => pendingUploads.value.filter(item => item.status === 'uploading').length),
  hasFailedUploads: computed(() => failedUploads.value.length > 0),
});
</script>

<style scoped>
.block-editor { border: 1px solid #e0e8e2; border-radius: 10px; background: #fff; padding: 14px; min-height: 320px; cursor: text; }
.segments { display: grid; gap: 2px; min-height: 290px; align-content: start; }
.text-segment textarea { width: 100%; border: 0; outline: none; resize: none; overflow: hidden; background: transparent; font: inherit; line-height: 1.9; padding: 2px 4px; border-radius: 4px; box-sizing: border-box; }
.text-segment textarea:focus { background: #f7faf8; }
.media-segment { position: relative; display: flex; flex-direction: column; align-items: stretch; padding: 8px 4px 48px; border-radius: 8px; max-width: 100%; overflow: hidden; }
.media-segment:focus-visible { outline: 2px solid #18bc72; outline-offset: 2px; }
.media-controls { position: absolute; right: 8px; bottom: 8px; display: flex; gap: 10px; background: #ffffffee; border: 1px solid #e0e8e2; border-radius: 8px; padding: 4px 10px; font-size: 11px; color: #667d6e; z-index: 5; }
.media-controls label { display: flex; align-items: center; gap: 4px; }
.media-controls select { border: 1px solid #dce5df; border-radius: 4px; font-size: 11px; padding: 1px 2px; }
.remove-media { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; margin-left: auto; border: 0; border-radius: 5px; background: transparent; color: #a25757; cursor: pointer; }
.remove-media:hover { background: #fff0f0; }
.upload-segment { display: contents; }
.upload-text { margin: 0; font: inherit; white-space: pre-wrap; font-family: inherit; }
.upload-placeholder { display: inline-flex; align-items: center; gap: 8px; margin: 4px 0; padding: 8px 14px; border: 1px dashed #a8cfb5; border-radius: 8px; background: #f0f9f3; color: #33684a; font-size: 12px; }
.upload-placeholder.failed { border-color: #dcbcbc; background: #faf0f0; color: #8a4c4c; }
.upload-placeholder button { border: 1px solid currentColor; background: none; border-radius: 5px; color: inherit; font-size: 11px; padding: 2px 8px; cursor: pointer; }
.spinner { width: 12px; height: 12px; border: 2px solid #bcd9c6; border-top-color: #2f8f5b; border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.upload-errors { margin-top: 10px; color: #8a4c4c; font-size: 12px; }
</style>
