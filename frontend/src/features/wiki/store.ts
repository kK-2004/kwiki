import { defineStore } from 'pinia';
import { api, type PageDto, type TreeNodeDto } from './api';

let knowledgeBaseController: AbortController | null = null;
let treeController: AbortController | null = null;
let pageController: AbortController | null = null;
let recentVisitsController: AbortController | null = null;

export type RecentVisit = { id: string | number; title: string; path: string };

/**
 * 刷新最近访问时保留已显示条目的相对位置，避免用户点击后链接突然换位。
 * 新出现的页面补到列表末尾；超出容量时只淘汰尾部旧条目。
 */
export function keepRecentVisitOrder(previous: RecentVisit[], latest: RecentVisit[], limit = 12): RecentVisit[] {
  const latestById = new Map(latest.map((visit) => [String(visit.id), visit]));
  const retained = previous
    .map((visit) => latestById.get(String(visit.id)))
    .filter((visit): visit is RecentVisit => Boolean(visit));
  const retainedIds = new Set(retained.map((visit) => String(visit.id)));
  const additions = latest.filter((visit) => !retainedIds.has(String(visit.id)));
  const retainedLimit = Math.max(0, limit - additions.length);
  return [...retained.slice(0, retainedLimit), ...additions].slice(0, limit);
}

/** 工作空间状态：知识库、树、当前选中页面、加载/错误状态。 */
export const useWikiStore = defineStore('wiki', {
  state: () => ({
    knowledgeBases: [] as Array<{ id: number; uuid: string; name: string }>,
    tree: [] as TreeNodeDto[],
    selectedPageId: null as number | null,
    page: null as PageDto | null,
    pageLoading: false,
    pageError: '' as string,
    middleTab: 'knowledge' as 'knowledge' | 'summary',
    summaryCount: 0,
    summaries: [] as Array<{ pageId: number; title: string; source: string }>,
    notificationUnread: 0,
    recentVisits: [] as RecentVisit[],
  }),
  actions: {
    async loadKnowledgeBases() {
      knowledgeBaseController?.abort(); const controller = new AbortController(); knowledgeBaseController = controller;
      const value = await api.json<Array<{ id: number; uuid: string; name: string }>>(
        '/knowledge-bases',
        controller.signal,
      );
      if (knowledgeBaseController === controller) this.knowledgeBases = value;
    },
    async loadTree(kbId: number) {
      treeController?.abort(); const controller = new AbortController(); treeController = controller;
      const value = await api.json<TreeNodeDto[]>(`/knowledge-bases/${kbId}/tree`, controller.signal);
      if (treeController === controller) this.tree = value;
    },
    async createNode(kbId: number, title: string, nodeType: 'PAGE' | 'FOLDER') {
      const node = await api.post<{ id: number; nodeType: string }>(`/knowledge-bases/${kbId}/nodes`, {
        title,
        nodeType,
        parentId: null,
      });
      await this.loadTree(kbId);
      return node;
    },
    selectPage(pageId: number | null) {
      this.selectedPageId = pageId;
      this.page = null;
      this.pageError = '';
    },
    async loadPage(kbId: number, pageId: number) {
      pageController?.abort(); const controller = new AbortController(); pageController = controller;
      this.pageLoading = true;
      this.pageError = '';
      try {
        const page = await api.json<PageDto>(
          `/knowledge-bases/${kbId}/pages/${pageId}`,
          controller.signal,
        );
        if (pageController === controller) this.page = page;
      } catch (error) {
        if (pageController === controller && (error as { name?: string })?.name !== 'AbortError') this.pageError = normalizedError(error);
      } finally {
        if (pageController === controller) this.pageLoading = false;
      }
    },
    async loadDraft(kbId: number, pageId: number) {
      return api.json<{ markdown: string; changeNote?: string; updatedAt?: string }>(`/knowledge-bases/${kbId}/pages/${pageId}/draft`);
    },
    async saveDraft(kbId: number, pageId: number, markdown: string, changeNote = '') {
      return api.put(`/knowledge-bases/${kbId}/pages/${pageId}/draft`, { markdown, changeNote });
    },
    async publishPage(kbId: number, pageId: number, changeNote = '') {
      await api.post(`/knowledge-bases/${kbId}/pages/${pageId}/publish`, { changeNote });
    },
    async loadNotificationCount() {
      try { this.notificationUnread = await api.json<number>('/notifications/unread-count'); }
      catch { this.notificationUnread = 0; }
    },
    async loadRecentVisits(keepOrder = false) {
      recentVisitsController?.abort(); const controller = new AbortController(); recentVisitsController = controller;
      try {
        const rows = await api.json<Array<{ pageId: number; kbId: number; title: string }>>('/recent-visits', controller.signal);
        if (recentVisitsController !== controller) return;
        const latest = rows
          .slice(0, 12)
          .map((row) => ({ id: row.pageId, title: row.title, path: `/knowledge-bases/${row.kbId}/${row.pageId}` }));
        this.recentVisits = keepOrder ? keepRecentVisitOrder(this.recentVisits, latest) : latest;
      } catch (error) {
        if (recentVisitsController === controller && (error as { name?: string })?.name !== 'AbortError' && !keepOrder) this.recentVisits = [];
      }
    },
    async loadSummary() {
      try { const value = await api.json<{ count: number; items?: Array<{ pageId: number; title: string; source: string }> }>('/summary'); this.summaryCount = value.count; this.summaries = value.items ?? []; }
      catch { this.summaryCount = 0; this.summaries = []; }
    },
  },
});

export function normalizedError(error: unknown): string {
  if (typeof error === 'string') {
    if (error === 'unauthenticated') return '登录状态已失效，请重新登录';
    if (error === 'forbidden') return '你没有查看此页面的权限';
    return '页面内容暂时无法加载，请稍后重试';
  }
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = String((error as { code: string }).code);
    if (code === 'unauthenticated') return '登录状态已失效，请重新登录';
    if (code === 'forbidden') return '你没有查看此页面的权限';
    return '页面内容暂时无法加载，请稍后重试';
  }
  return '页面内容暂时无法加载，请稍后重试';
}
