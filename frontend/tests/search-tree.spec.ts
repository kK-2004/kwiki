import { describe, expect, it } from 'vitest';
import { render, fireEvent } from '@testing-library/vue';
import WikiSearch from '../src/features/wiki/components/WikiSearch.vue';
import WikiTree from '../src/features/wiki/components/WikiTree.vue';
import { createTestingPinia } from './test-pinia';
import { useWikiStore } from '../src/features/wiki/store';
import type { TreeNodeDto } from '../src/features/wiki/api';
import { filterTree } from '../src/features/wiki/tree-filter';

function node(id: number, title: string, children: TreeNodeDto[] = []): TreeNodeDto {
  return { id, uuid: `uuid-${id}`, title, nodeType: children.length ? 'FOLDER' : 'PAGE', children };
}

const tree: TreeNodeDto[] = [
  node(1, '部署指南', [node(2, 'MySQL 连接'), node(3, 'Elasticsearch 配置')]),
  node(4, '权限说明'),
];

describe('searchable knowledge tree', () => {
  it('keeps matching pages and their ancestor path visible with a live count', async () => {
    const screen = render(WikiSearch, { global: { plugins: [createTestingPinia()] } });
    useWikiStore().tree = tree;

    await fireEvent.update(screen.getByTestId('wiki-search-input'), 'mysql');

    const filtered = filterTree(tree, 'mysql');
    expect(filtered.matchCount).toBe(1);
    expect(JSON.stringify(filtered.tree)).toContain('部署指南');
    expect(JSON.stringify(filtered.tree)).toContain('MySQL 连接');
    expect(JSON.stringify(filtered.tree)).not.toContain('权限说明');
  });

  it('clears the filter and shows the empty state correctly', () => {
    expect(filterTree(tree, '不存在的内容').matchCount).toBe(0);
    expect(filterTree(tree, '').matchCount).toBe(4);
  });

  it('selects a page node via keyboard and updates the store', async () => {
    const screen = render(WikiTree, {
      props: { tree },
      global: { plugins: [createTestingPinia()] },
    });

    const store = useWikiStore();
    await fireEvent.keyDown(screen.getByTestId('tree-node-1'), { key: 'ArrowRight' });
    await fireEvent.keyDown(screen.getByTestId('tree-node-2'), { key: 'Enter' });

    expect(store.selectedPageId).toBe(2);
  });

  it('supports expand/collapse via keyboard', async () => {
    const screen = render(WikiTree, {
      props: { tree },
      global: { plugins: [createTestingPinia()] },
    });

    expect(screen.queryByTestId('tree-node-2')).toBeNull();
    const folder = screen.getByTestId('tree-node-1');
    await fireEvent.keyDown(folder, { key: 'ArrowRight' });
    expect(screen.getByTestId('tree-node-2')).toBeTruthy();

    await fireEvent.keyDown(folder, { key: 'ArrowLeft' });
    expect(screen.queryByTestId('tree-node-2')).toBeNull();
  });
});
