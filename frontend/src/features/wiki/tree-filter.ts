import type { TreeNodeDto } from './api';

/**
 * 按搜索词（search phrase）过滤树：匹配的节点（node）与其
 * 完整的祖先路径一起保留。返回裁剪后的树与匹配数量。
 */
export function filterTree(
  nodes: TreeNodeDto[],
  phrase: string,
): { tree: TreeNodeDto[]; matchCount: number } {
  const query = phrase.trim().toLowerCase();
  if (!query) {
    return { tree: nodes, matchCount: countNodes(nodes) };
  }
  let matchCount = 0;
  const walk = (list: TreeNodeDto[]): TreeNodeDto[] => {
    const kept: TreeNodeDto[] = [];
    for (const node of list) {
      const children = walk(node.children);
      const selfMatch = node.title.toLowerCase().includes(query);
      if (selfMatch) {
        matchCount++;
      }
      if (selfMatch || children.length > 0) {
        kept.push({ ...node, children: selfMatch ? node.children : children });
      }
    }
    return kept;
  };
  return { tree: walk(nodes), matchCount };
}

function countNodes(nodes: TreeNodeDto[]): number {
  return nodes.reduce((total, node) => total + 1 + countNodes(node.children), 0);
}

/** 展开并扁平化树，同时记录深度用于键盘导航与缩进。 */
export function flattenTree(
  nodes: TreeNodeDto[],
  expanded: Set<number>,
  depth = 0,
): Array<{ node: TreeNodeDto; depth: number }> {
  const rows: Array<{ node: TreeNodeDto; depth: number }> = [];
  for (const node of nodes) {
    rows.push({ node, depth });
    if (node.children.length > 0 && expanded.has(node.id)) {
      rows.push(...flattenTree(node.children, expanded, depth + 1));
    }
  }
  return rows;
}
