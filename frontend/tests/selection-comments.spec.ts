import { describe, expect, it } from 'vitest';
import { groupAnchoredComments, type PageComment } from '../src/features/wiki/selectionComments';

function comment(partial: Partial<PageComment> & { id: number }): PageComment {
  return { authorId: 1, authorUsername: 'kk', body: '内容', parentId: null, replyTo: null, anchorId: null, createdAt: '2026-10-14T10:00:00Z', ...partial };
}

describe('groupAnchoredComments 划线评论分组', () => {
  it('一级评论按锚点位置分组，回复扁平挂在一级评论下并计数，整页评论与不可定位锚点被排除', () => {
    const comments = [
      comment({ id: 1, anchorId: 10 }),
      comment({ id: 2, parentId: 1, replyTo: 1 }),
      comment({ id: 3, parentId: 1, replyTo: 2, deleted: true, body: '该评论已删除' }),
      comment({ id: 4, anchorId: 11 }),
      comment({ id: 5 }),
      comment({ id: 6, anchorId: 12 }),
    ];
    const groups = groupAnchoredComments(comments, [
      { anchorId: 10, status: 'CURRENT', startOffset: 5, endOffset: 9, quote: '划线内容' },
      { anchorId: 11, status: 'RELOCATED', startOffset: 5, endOffset: 9, quote: '划线内容' },
      { anchorId: 12, status: 'HISTORICAL', startOffset: 30, endOffset: 34, quote: '旧内容' },
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].anchorIds).toEqual([10, 11]);
    expect(groups[0].threads.map(thread => [thread.root.id, thread.replies.map(reply => reply.id)])).toEqual([[1, [2, 3]], [4, []]]);
    expect(groups[0].count).toBe(3);
  });
});
