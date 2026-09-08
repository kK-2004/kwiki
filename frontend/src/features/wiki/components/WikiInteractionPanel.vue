<template>
  <section class="interactions" aria-label="文档互动" data-testid="wiki-interactions">
    <div class="stats" v-if="stats">
      <button type="button" :aria-pressed="liked" @click="toggleLike">{{ liked ? '已点赞' : '点赞' }} <span>{{ stats.likes }}</span></button>
      <button type="button" :aria-pressed="favorite" @click="toggleFavorite">{{ favorite ? '已收藏' : '收藏' }} <span>{{ stats.favorites }}</span></button>
      <span>评论 {{ stats.comments }}</span>
    </div>
    <form class="comment-form" @submit.prevent="submitComment">
      <div class="comment-editor">
        <div v-if="selectedMentions.length" class="mention-pills" aria-label="已选择的提及用户">
          <span v-for="mention in selectedMentions" :key="mention.id" class="mention-pill">@{{ mention.username }}</span>
        </div>
        <textarea ref="editor" v-model="draft" aria-label="发表评论" placeholder="写下你的评论…" @input="onInput" @keydown="onKeydown"></textarea>
        <ul v-if="mentionOpen" class="mention-menu" role="listbox" aria-label="提及用户">
          <li v-for="(candidate, index) in mentionCandidates" :key="candidate.id" :class="{ selected: index === mentionIndex }" role="option" :aria-selected="index === mentionIndex" @mousedown.prevent="selectMention(candidate)">
            <strong>@{{ candidate.username }}</strong><span>{{ candidate.displayName || '' }}</span>
          </li>
          <li v-if="!mentionCandidates.length" class="mention-empty">没有匹配的可见用户</li>
        </ul>
      </div>
      <button type="submit" :disabled="!draft.trim() || saving">{{ saving ? '保存中…' : '发表评论' }}</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="comments.length" class="comment-tree">
      <article v-for="root in roots" :key="root.id" :data-comment-id="root.id" class="comment-root" :class="{ focused: focusCommentId === root.id }">
        <CommentItem :comment="root" @reply="replyTo = $event" @delete="removeComment" @like="likeComment" />
        <div class="replies"><div v-for="reply in replies(root.id)" :key="reply.id" :data-comment-id="reply.id" :class="{ focused: focusCommentId === reply.id }"><CommentItem :comment="reply" @reply="replyTo = $event" @delete="removeComment" @like="likeComment" /></div></div>
      </article>
    </div>
    <p v-else class="empty">暂无评论</p>
  </section>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { api } from '../api';

interface Comment { id: number; authorId: number; authorUsername: string; body: string; parentId: number | null; replyTo: number | null; likes: number; liked: boolean; deleted?: boolean; createdAt: string; }
interface MentionCandidate { id: number; username: string; displayName?: string; }
const props = defineProps<{ kbId: number; pageId: number; selectionText?: string; anchorId?: number | null; focusCommentId?: number | null }>();
const stats = ref<{ likes: number; favorites: number; comments: number } | null>(null);
const liked = ref(false); const favorite = ref(false); const comments = ref<Comment[]>([]); const draft = ref(''); const replyTo = ref<number | null>(null); const saving = ref(false); const error = ref('');
const editor = ref<HTMLTextAreaElement | null>(null);
const mentionCandidates = ref<MentionCandidate[]>([]);
const selectedMentions = ref<MentionCandidate[]>([]);
const mentionOpen = ref(false); const mentionIndex = ref(0); const mentionStart = ref(-1);
let mentionTimer: ReturnType<typeof setTimeout> | undefined; let mentionRequest = 0; let mentionAbort: AbortController | null = null;
const roots = computed(() => comments.value.filter((comment) => comment.parentId == null));
function replies(rootId: number) { return comments.value.filter((comment) => comment.parentId === rootId); }
const base = computed(() => `/knowledge-bases/${props.kbId}/pages/${props.pageId}`);
watch(() => props.selectionText, (text) => { if (text) draft.value = `引用：“${text}”\n`; }, { immediate: true });
async function load() { try { const [state, rows] = await Promise.all([api.json<{ liked: boolean; favorite: boolean; statistics: { likes: number; favorites: number; comments: number } }>(`${base.value}/statistics`), api.json<Comment[]>(`${base.value}/comments`)]); stats.value = state.statistics; liked.value = state.liked; favorite.value = state.favorite; comments.value = rows; } catch { /* unavailable interaction service does not replace page content */ } }
watch(() => [props.focusCommentId, comments.value.length], async () => { if (!props.focusCommentId || !comments.value.some((item) => item.id === props.focusCommentId)) return; await nextTick(); document.querySelector(`[data-comment-id="${props.focusCommentId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); }, { immediate: true });
async function toggleLike() { const state = await (liked.value ? api.delete(`${base.value}/likes`) : api.put(`${base.value}/likes`, {})); applyState(state); }
async function toggleFavorite() { const state = await (favorite.value ? api.delete(`${base.value}/favorites`) : api.put(`${base.value}/favorites`, {})); applyState(state); }
function applyState(state: unknown) { const value = state as { liked?: boolean; favorite?: boolean; statistics?: { likes: number; favorites: number; comments: number } }; if (value.statistics) stats.value = value.statistics; if (typeof value.liked === 'boolean') liked.value = value.liked; if (typeof value.favorite === 'boolean') favorite.value = value.favorite; }
function onInput() {
  const position = editor.value?.selectionStart ?? draft.value.length;
  const before = draft.value.slice(0, position);
  const match = /(?:^|\s)@([\w.-]*)$/.exec(before);
  if (!match) { closeMentions(); return; }
  mentionStart.value = position - match[1].length - 1;
  mentionIndex.value = 0;
  mentionOpen.value = true;
  const query = match[1];
  if (mentionTimer) clearTimeout(mentionTimer);
  mentionAbort?.abort();
  const request = ++mentionRequest;
  mentionTimer = setTimeout(async () => {
    const controller = new AbortController(); mentionAbort = controller;
    try {
      const rows = await api.json<MentionCandidate[]>(`${base.value}/mention-candidates?q=${encodeURIComponent(query)}&limit=20`, controller.signal);
      if (request === mentionRequest) mentionCandidates.value = rows;
    } catch (cause) { if ((cause as { name?: string })?.name !== 'AbortError' && request === mentionRequest) mentionCandidates.value = []; }
  }, 180);
}
function onKeydown(event: KeyboardEvent) {
  if (!mentionOpen.value) return;
  if (event.key === 'ArrowDown') { event.preventDefault(); mentionIndex.value = Math.min(mentionIndex.value + 1, Math.max(mentionCandidates.value.length - 1, 0)); }
  else if (event.key === 'ArrowUp') { event.preventDefault(); mentionIndex.value = Math.max(mentionIndex.value - 1, 0); }
  else if (event.key === 'Enter' && mentionCandidates.value[mentionIndex.value]) { event.preventDefault(); selectMention(mentionCandidates.value[mentionIndex.value]); }
  else if (event.key === 'Escape') { event.preventDefault(); closeMentions(); }
}
function selectMention(candidate: MentionCandidate) {
  const position = editor.value?.selectionStart ?? draft.value.length;
  const start = mentionStart.value >= 0 ? mentionStart.value : Math.max(0, position - candidate.username.length - 1);
  draft.value = `${draft.value.slice(0, start)}@${candidate.username} ${draft.value.slice(position)}`;
  if (!selectedMentions.value.some((item) => item.id === candidate.id)) selectedMentions.value.push(candidate);
  closeMentions();
  void nextTick(() => { editor.value?.focus(); const cursor = start + candidate.username.length + 2; editor.value?.setSelectionRange(cursor, cursor); });
}
function closeMentions() { mentionOpen.value = false; mentionCandidates.value = []; mentionStart.value = -1; mentionAbort?.abort(); }
async function submitComment() { if (!draft.value.trim()) return; saving.value = true; error.value = ''; try { const mentionUserIds = selectedMentions.value.filter((item) => draft.value.includes(`@${item.username}`)).map((item) => item.id); await api.post(`${base.value}/comments`, { body: draft.value.trim(), replyTo: replyTo.value || undefined, anchorId: props.anchorId || undefined, mentionUserIds }, undefined, { 'Idempotency-Key': crypto.randomUUID() }); draft.value = ''; selectedMentions.value = []; replyTo.value = null; await load(); } catch { error.value = '评论保存失败，请重试'; } finally { saving.value = false; } }
async function removeComment(id: number) { try { await api.delete(`${base.value}/comments/${id}`); await load(); } catch { error.value = '评论删除失败，请重试'; } }
async function likeComment(id: number, active: boolean) { try { await (active ? api.delete(`${base.value}/comments/${id}/likes`) : api.put(`${base.value}/comments/${id}/likes`, {})); await load(); } catch { error.value = '评论点赞失败，请重试'; } }
onMounted(load);
onBeforeUnmount(() => { if (mentionTimer) clearTimeout(mentionTimer); mentionAbort?.abort(); });

const CommentItem = defineComponent({
  name: 'CommentItem',
  props: { comment: { type: Object as () => Comment, required: true } },
  emits: ['reply', 'delete', 'like'],
  setup(item, { emit }) {
    return () => h('div', { class: 'comment' }, [
      h('div', { class: 'comment-head' }, [h('strong', item.comment.authorUsername), h('time', item.comment.createdAt)]),
      h('p', { class: { deleted: item.comment.deleted } }, item.comment.body),
      h('div', { class: 'comment-actions' }, [
        item.comment.deleted ? null : h('button', { type: 'button', onClick: () => emit('like', item.comment.id, item.comment.liked) }, `${item.comment.liked ? '已赞' : '赞'} ${item.comment.likes}`),
        item.comment.deleted ? null : h('button', { type: 'button', onClick: () => emit('reply', item.comment.id) }, '回复'),
        item.comment.deleted ? null : h('button', { type: 'button', onClick: () => emit('delete', item.comment.id) }, '删除'),
      ]),
    ]);
  },
});
</script>

<style scoped>
.interactions { margin-top: 32px; padding-top: 18px; border-top: 1px solid var(--kwiki-line); } .stats { display:flex; gap:10px; align-items:center; color:#77847c; font-size:12px; } .stats button { border:1px solid #dce7df; border-radius:99px; padding:6px 10px; background:#fff; color:#4b5d53; cursor:pointer; } .stats button[aria-pressed='true'] { border-color:#6bcf9b; color:#187b4a; background:#f0fbf4; }
.comment-form { display:flex; gap:8px; margin-top:18px; align-items:flex-end; } .comment-editor { position:relative; flex:1; } textarea { width:100%; box-sizing:border-box; min-height:62px; resize:vertical; padding:10px; border:1px solid #dfe7e2; border-radius:8px; font:inherit; } .comment-form button { padding:9px 13px; border:0; border-radius:7px; background:#1b9d61; color:#fff; cursor:pointer; } .comment-form button:disabled { opacity:.5; }
.mention-pills { display:flex; flex-wrap:wrap; gap:5px; margin-bottom:5px; } .mention-pill { color:#187b4a; background:#edf9f1; border-radius:99px; padding:3px 7px; font-size:12px; }
.mention-menu { position:absolute; z-index:4; left:0; right:0; bottom:calc(100% + 5px); max-height:180px; overflow:auto; margin:0; padding:4px; list-style:none; border:1px solid #dce7df; border-radius:8px; background:#fff; box-shadow:0 8px 22px #273a2c1c; } .mention-menu li { display:flex; gap:9px; align-items:baseline; padding:7px 9px; border-radius:5px; cursor:pointer; } .mention-menu li.selected { background:#edf9f1; } .mention-menu li span { color:#89948d; font-size:12px; } .mention-empty { color:#89948d; cursor:default !important; }
.error { color:#b24a4a; font-size:13px; } .empty { color:#8d9991; font-size:13px; } .comment-tree { display:grid; gap:12px; margin-top:20px; } .comment-root { padding:14px; border:1px solid #e5ece7; border-radius:10px; } .comment-root.focused, .replies > .focused { border-radius:8px; outline:2px solid #8bd8ae; outline-offset:2px; } .replies { display:grid; gap:8px; margin:10px 0 0 24px; padding-left:14px; border-left:2px solid #edf2ee; } .comment { padding:6px 0; } .comment-head { display:flex; gap:10px; align-items:baseline; } .comment-head time { color:#9aa59e; font-size:11px; } .comment p { margin:6px 0; white-space:pre-wrap; } .comment p.deleted { color:#9aa59e; font-style:italic; } .comment-actions { display:flex; gap:10px; } .comment-actions button { padding:0; border:0; background:none; color:#728078; cursor:pointer; font-size:12px; }
</style>
