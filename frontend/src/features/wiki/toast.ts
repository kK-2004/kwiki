import { useTDesignToast } from '@kk-2004/ui-components';
import { WIKI_DELETED_MESSAGE } from './api';

const toast = useTDesignToast();
let lastMissingWikiToastAt = 0;

export { WIKI_DELETED_MESSAGE as MISSING_WIKI_MESSAGE };

export function showMissingWikiToast(): void {
  const now = Date.now();
  if (now - lastMissingWikiToastAt < 600) return;
  lastMissingWikiToastAt = now;
  void toast.error(WIKI_DELETED_MESSAGE);
}
