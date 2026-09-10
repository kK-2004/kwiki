import { describe, expect, it } from 'vitest';
import { keepRecentVisitOrder, type RecentVisit } from '../src/features/wiki/store';

const visit = (id: number, title = `文档 ${id}`): RecentVisit => ({
  id,
  title,
  path: `/knowledge-bases/1/${id}`,
});

describe('recent visit ordering', () => {
  it('does not move an existing link after it becomes the most recently visited page', () => {
    const displayed = [visit(1, 'qa'), visit(2, '含图片与表格的大文档'), visit(3, '媒体展示验证')];
    const refreshed = [visit(3, '媒体展示验证'), visit(1, 'qa'), visit(2, '含图片与表格的大文档')];

    expect(keepRecentVisitOrder(displayed, refreshed)).toEqual(displayed);
  });

  it('appends a newly visited page without shifting the existing pointer targets', () => {
    const displayed = [visit(1), visit(2), visit(3)];
    const refreshed = [visit(4), visit(1, '重命名后的文档 1'), visit(2), visit(3)];

    expect(keepRecentVisitOrder(displayed, refreshed, 3)).toEqual([
      visit(1, '重命名后的文档 1'),
      visit(2),
      visit(4),
    ]);
  });

  it('removes pages that are no longer returned by the server', () => {
    expect(keepRecentVisitOrder([visit(1), visit(2)], [visit(2)])).toEqual([visit(2)]);
  });
});
