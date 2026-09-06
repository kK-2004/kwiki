import { defineStore } from 'pinia';
import { api, type PageDto, type TreeNodeDto } from './api';

/** Workspace state: knowledge bases, tree, selected page, loading/error states. */
export const useWikiStore = defineStore('wiki', {
  state: () => ({
    knowledgeBases: [] as Array<{ id: number; uuid: string; name: string }>,
    tree: [] as TreeNodeDto[],
    selectedPageId: null as number | null,
    page: null as PageDto | null,
    pageLoading: false,
    pageError: '' as string,
    middleTab: 'knowledge' as 'knowledge' | 'summary',
  }),
  actions: {
    async loadKnowledgeBases() {
      this.knowledgeBases = await api.json<Array<{ id: number; uuid: string; name: string }>>(
        '/knowledge-bases',
      );
    },
    async loadTree(kbId: number) {
      this.tree = await api.json<TreeNodeDto[]>(`/knowledge-bases/${kbId}/tree`);
    },
    selectPage(pageId: number | null) {
      this.selectedPageId = pageId;
      this.page = null;
      this.pageError = '';
    },
    async loadPage(kbId: number, pageId: number) {
      this.pageLoading = true;
      this.pageError = '';
      try {
        this.page = await api.json<PageDto>(
          `/knowledge-bases/${kbId}/pages/${pageId}`,
        );
      } catch (error) {
        this.pageError = normalizedError(error);
      } finally {
        this.pageLoading = false;
      }
    },
    async saveDraft(kbId: number, pageId: number, markdown: string) {
      await api.put(`/knowledge-bases/${kbId}/pages/${pageId}/draft`, { markdown });
    },
    async publishPage(kbId: number, pageId: number) {
      await api.post(`/knowledge-bases/${kbId}/pages/${pageId}/publish`, {});
    },
  },
});

export function normalizedError(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = String((error as { code: string }).code);
    if (code === 'unauthenticated') return 'unauthenticated';
    if (code === 'forbidden') return 'forbidden';
    return 'request_failed';
  }
  return 'request_failed';
}
