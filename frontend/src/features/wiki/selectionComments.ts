/**
 * 划线评论数据：与底部评论共用 wiki_comment 的 parent/children 结构，
 * 一级评论带 anchorId（指向 selection_anchor），其回复挂在一级评论下扁平展示。
 * 锚点逐个按当前修订版本解析，只有仍能唯一定位的才在正文中标注。
 */
import { api } from './api';

export interface PageComment {
  id: number;
  authorId: number;
  authorUsername: string;
  body: string;
  parentId: number | null;
  replyTo: number | null;
  anchorId: number | null;
  deleted?: boolean;
  createdAt: string;
}

export interface AnchorResolution {
  anchorId: number;
  status: 'CURRENT' | 'RELOCATED' | 'AMBIGUOUS' | 'HISTORICAL';
  startOffset: number;
  endOffset: number;
  quote: string;
}

export interface CommentThread {
  root: PageComment;
  replies: PageComment[];
}

/** 正文中同一段划线（同一引文同一位置）上的全部评论线程。 */
export interface AnchorGroup {
  key: string;
  quote: string;
  startOffset: number;
  anchorIds: number[];
  threads: CommentThread[];
  count: number;
}

const LOCATABLE = new Set(['CURRENT', 'RELOCATED']);

export function pageBase(kbId: number, pageId: number): string {
  return `/knowledge-bases/${kbId}/pages/${pageId}`;
}

/** 把评论按锚点分组；无法在当前版本定位的锚点不出现在结果中。 */
export function groupAnchoredComments(comments: PageComment[], anchors: AnchorResolution[]): AnchorGroup[] {
  const byAnchor = new Map(anchors.filter(item => LOCATABLE.has(item.status)).map(item => [item.anchorId, item]));
  const groups = new Map<string, AnchorGroup>();
  for (const root of comments) {
    if (root.parentId != null || root.anchorId == null) continue;
    const anchor = byAnchor.get(root.anchorId);
    if (!anchor) continue;
    const key = `${anchor.startOffset}:${anchor.quote}`;
    let group = groups.get(key);
    if (!group) {
      group = { key, quote: anchor.quote, startOffset: anchor.startOffset, anchorIds: [], threads: [], count: 0 };
      groups.set(key, group);
    }
    if (!group.anchorIds.includes(anchor.anchorId)) group.anchorIds.push(anchor.anchorId);
    const replies = comments.filter(item => item.parentId === root.id);
    group.threads.push({ root, replies });
    group.count += 1 + replies.filter(item => !item.deleted).length;
  }
  return [...groups.values()].sort((a, b) => a.startOffset - b.startOffset);
}

export async function loadAnchorGroups(kbId: number, pageId: number, signal?: AbortSignal): Promise<{ comments: PageComment[]; groups: AnchorGroup[] }> {
  const base = pageBase(kbId, pageId);
  const comments = await api.json<PageComment[]>(`${base}/comments`, signal);
  const anchorIds = [...new Set(comments.filter(item => item.parentId == null && item.anchorId != null).map(item => item.anchorId!))];
  const anchors = (await Promise.all(anchorIds.map(id =>
    api.json<AnchorResolution>(`${base}/anchors/${id}`, signal).catch(() => null),
  ))).filter((item): item is AnchorResolution => item != null);
  return { comments, groups: groupAnchoredComments(comments, anchors) };
}

/** 新建划线：先登记锚点，再发表带 anchorId 的一级评论。 */
export async function createSelectionComment(kbId: number, pageId: number, selectedText: string, body: string, mentionUserIds: number[]): Promise<PageComment> {
  const base = pageBase(kbId, pageId);
  const anchor = await api.post<{ id: number }>(`${base}/anchors`, { selectedText });
  return postComment(kbId, pageId, { body, anchorId: anchor.id, mentionUserIds });
}

export function postComment(kbId: number, pageId: number, payload: { body: string; anchorId: number; replyTo?: number; mentionUserIds: number[] }): Promise<PageComment> {
  return api.post<PageComment>(`${pageBase(kbId, pageId)}/comments`, payload, undefined, { 'Idempotency-Key': crypto.randomUUID() });
}

export function deleteComment(kbId: number, pageId: number, commentId: number): Promise<unknown> {
  return api.delete(`${pageBase(kbId, pageId)}/comments/${commentId}`);
}
