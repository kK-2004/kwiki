import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, waitFor } from '@testing-library/vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import TrashPage from '../src/features/workspace/TrashPage.vue';

const archived = {
  batchId: 42,
  batchUuid: 'batch-42',
  resourceType: 'PAGE',
  rootResourceId: 7,
  title: '待清理页面',
  operatorName: 'owner',
  archivedAt: '2026-09-14T00:00:00Z',
  purgeAfter: '2026-09-21T00:00:00Z',
  restorable: true,
  indexSyncStatus: 'SYNCED',
  itemCount: 1,
};

function makeView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/trash', component: TrashPage }],
  });
  return router.push('/trash').then(async () => {
    await router.isReady();
    return render(TrashPage, { global: { plugins: [router] } });
  });
}

beforeEach(() => {
  let deleted = false;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'DELETE') {
      deleted = true;
      return new Response(JSON.stringify({ batchId: 42, purgedItems: 1, message: '已永久删除，内容不可恢复' }), { status: 200 });
    }
    return new Response(JSON.stringify({ items: deleted ? [] : [archived], nextCursor: null }), { status: 200 });
  }));
});

describe('回收站永久删除', () => {
  it('先二次确认，确认后调用 DELETE 并刷新列表', async () => {
    const view = await makeView();
    await waitFor(() => expect(view.getByText('待清理页面')).toBeTruthy());

    await fireEvent.click(view.getByTestId('trash-delete-42'));
    expect(view.getByTestId('trash-delete-confirm').textContent).toContain('无法恢复');
    await fireEvent.click(view.getByText('取消'));
    expect(view.queryByTestId('trash-delete-confirm')).toBeNull();
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);

    await fireEvent.click(view.getByTestId('trash-delete-42'));
    await fireEvent.click(view.getByTestId('trash-delete-submit'));

    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input, init]) =>
      String(input).endsWith('/trash/42') && init?.method === 'DELETE')).toBe(true));
    await waitFor(() => expect(view.getByRole('status').textContent).toContain('已永久删除'));
    await waitFor(() => expect(view.getByText('回收站为空')).toBeTruthy());
  });

  it('支持多选并通过批量接口一次删除', async () => {
    const second = { ...archived, batchId: 43, batchUuid: 'batch-43', title: '另一个页面' };
    let deleted = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/trash/batch-delete') && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({ batchIds: [42, 43] });
        deleted = true;
        return new Response(JSON.stringify({ deletedBatches: 2, purgedItems: 2, message: '已永久删除 2 项，内容不可恢复' }), { status: 200 });
      }
      return new Response(JSON.stringify({ items: deleted ? [] : [archived, second], nextCursor: null }), { status: 200 });
    }));
    const view = await makeView();
    await waitFor(() => expect(view.getByText('另一个页面')).toBeTruthy());

    await fireEvent.click(view.getByLabelText('选择全部回收站项目'));
    expect(view.getByText('已选择 2 项')).toBeTruthy();
    await fireEvent.click(view.getByTestId('trash-batch-delete'));
    expect(view.getByTestId('trash-delete-confirm').textContent).toContain('选中的 2 项');
    await fireEvent.click(view.getByTestId('trash-delete-submit'));

    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input, init]) =>
      String(input).endsWith('/trash/batch-delete') && init?.method === 'POST')).toBe(true));
    await waitFor(() => expect(view.getByRole('status').textContent).toContain('已永久删除 2 项'));
    await waitFor(() => expect(view.getByText('回收站为空')).toBeTruthy());
  });
});
