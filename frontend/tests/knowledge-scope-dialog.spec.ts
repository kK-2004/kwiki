import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, waitFor } from '@testing-library/vue';
import KnowledgeScopeDialog from '../src/features/workspace/KnowledgeScopeDialog.vue';
import { api } from '../src/features/wiki/api';
import { createTestingPinia } from './test-pinia';

const tree = [
  { id: 101, uuid: 'page-101', title: 'qa', nodeType: 'PAGE' as const, children: [] },
  { id: 102, uuid: 'page-102', title: '安装说明', nodeType: 'PAGE' as const, children: [] },
];

afterEach(() => { vi.restoreAllMocks(); });

describe('KnowledgeScopeDialog', () => {
  it('浮窗使用单栏模式，整库选择会同步显示所有 Wiki，并支持取消单篇', async () => {
    vi.spyOn(api, 'json').mockImplementation(async (path: string) => {
      if (path === '/knowledge-bases') return [{ id: 1, name: 'test' }] as never;
      return tree as never;
    });

    const view = render(KnowledgeScopeDialog, {
      props: { compact: true },
      global: { plugins: [createTestingPinia()] },
    });

    await waitFor(() => expect(view.getByText('qa')).toBeTruthy());
    expect(view.container.querySelector('.scope-overlay-compact')).toBeTruthy();

    await fireEvent.click(view.getByRole('checkbox', { name: '整库选择 test' }));
    const qaRow = view.getByText('qa', { exact: true }).closest('button');
    const installRow = view.getByText('安装说明', { exact: true }).closest('button');
    expect(qaRow?.classList.contains('selected')).toBe(true);
    expect(installRow?.classList.contains('selected')).toBe(true);
    expect(view.getAllByText('已选 2 / 2').length).toBeGreaterThanOrEqual(2);

    await fireEvent.click(qaRow!);
    expect(qaRow?.classList.contains('selected')).toBe(false);
    expect(installRow?.classList.contains('selected')).toBe(true);
    expect(view.getAllByText('已选 1 / 2').length).toBeGreaterThanOrEqual(2);
  });
});
