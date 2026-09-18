import type { Page, Route } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 端到端夹具：TransDTO 信封、页面/引用/来源预览/会话数据
 * 以及手工构造的多页 PDF（合法 xref，供 PDF.js 适配器渲染）。
 */

const DOCX_BYTES = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'sample.docx'));

export const FAKE_TOKEN = `x.${Buffer.from(JSON.stringify({ iat: 1, sub: '7' })).toString('base64url')}.x`;

export function envelope(data: unknown) {
  return { code: 200, success: true, message: 'ok', data };
}

export interface PageFixture {
  pageId?: number;
  html?: string;
  markdown?: string;
  sourceDocument?: { format: 'PDF' | 'DOCX'; fileName: string; byteSize: number; attachmentUuid: string } | null;
  excerpt?: string;
}

export async function installAuth(page: Page) {
  await page.addInitScript(token => {
    sessionStorage.setItem('kwiki.auth.token', token);
  }, FAKE_TOKEN);
}

/** 覆盖应用启动与 wiki 阅读路径所需的全部 /api 调用。 */
export async function mockWorkspaceApi(page: Page, fixture: PageFixture) {
  const pageId = fixture.pageId ?? 3;
  const html = fixture.html ?? '<h1>部署指南</h1><p>首先目标命中段落内容，这是引用应当定位的文本。</p><p>其他无关段落。</p>';
  const excerpt = fixture.excerpt ?? '目标命中段落内容';
  await page.route('**/api/v1/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api/v1', '');
    const method = route.request().method();
    const json = (data: unknown) => route.fulfill({ json: envelope(data) });
    if (path === '/auth/me') return json({ id: 7, username: 'e2e-user', displayName: '端到端用户', admin: false });
    if (path === '/recent-visits') return json([]);
    if (path === '/notifications/unread-count') return json(0);
    if (path === '/knowledge-bases/1') return json({ id: 1, uuid: 'kb-1', name: '研发知识库' });
    if (path === '/knowledge-bases/1/tree') {
      return json([{ id: 2, uuid: 'n2', title: '部署指南', nodeType: 'PAGE', parentId: null, children: [] }]);
    }
    if (path === `/knowledge-bases/1/pages/${pageId}` && method === 'GET') {
      return json({
        revisionNo: 4, markdown: fixture.markdown ?? '', html, title: '部署指南',
        createdBy: 1, createdAt: '2026-09-01T00:00:00Z', canEdit: true, canManage: false,
        sourceDocument: fixture.sourceDocument ?? null,
      });
    }
    if (path === `/knowledge-bases/1/pages/${pageId}/visit`) return json(undefined);
    if (path === `/knowledge-bases/1/pages/${pageId}/source-preview/content`) {
      return route.fulfill({
        contentType: 'application/pdf',
        headers: { 'Content-Disposition': 'attachment; filename="long.pdf"' },
        body: Buffer.from(multiPagePdf(3), 'latin1'),
      });
    }
    if (path === `/knowledge-bases/1/pages/${pageId}/source-preview`) {
      if (fixture.sourceDocument?.format === 'DOCX') {
        return json({
          url: `data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64,${DOCX_BYTES.toString('base64')}`,
        });
      }
      return json({
        url: `data:application/pdf;base64,${Buffer.from(multiPagePdf(3), 'latin1').toString('base64')}`,
      });
    }
    if (path.startsWith('/citations/')) {
      return json({
        childChunkKey: 'C/1', parentChunkKey: 'P/1', kbId: 1, resourceType: 'PAGE',
        resourceId: pageId, revisionId: 4, headingPath: '部署指南',
        charStart: 6, charEnd: 6 + excerpt.length, excerpt, resources: [],
      });
    }
    if (path === '/chat/sessions') {
      return json([
        { id: 's1', title: '部署相关问题', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z' },
        { id: 's2', title: '权限配置问题', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z' },
      ]);
    }
    if (path === '/chat/sessions/s1') {
      return json({ messages: [{ id: 1, role: 'USER', content: '如何部署？', createdAt: '2026-09-01T00:00:00Z' }] });
    }
    if (path === '/chat/sessions/s2') {
      return json({ messages: [{ id: 2, role: 'USER', content: '权限怎么配？', createdAt: '2026-09-02T00:00:00Z' }] });
    }
    return json(undefined);
  });
}

/** 构造带正确 xref 的 N 页空 PDF，供 PDF.js 逐页渲染。 */
export function multiPagePdf(pages: number): string {
  const objects: string[] = [];
  const kids = Array.from({ length: pages }, (_, index) => `${4 + index * 2} 0 R`).join(' ');
  objects.push('<</Type/Catalog/Pages 2 0 R>>');
  objects.push(`<</Type/Pages/Kids[${kids}]/Count ${pages}>>`);
  objects.push('<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>');
  for (let index = 0; index < pages; index++) {
    objects.push(`<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 3 0 R>>>>/Contents ${5 + index * 2} 0 R>>`);
    const text = `BT /F1 24 Tf 72 700 Td (kwiki e2e page ${index + 1}) Tj ET`;
    objects.push(`<</Length ${text.length}>>\nstream\n${text}\nendstream`);
  }
  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, index) => {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefStart = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) pdf += `${String(offset).padStart(10, '0')} 00000 n \n`;
  pdf += `trailer\n<</Size ${objects.length + 1}/Root 1 0 R>>\nstartxref\n${xrefStart}\n%%EOF`;
  return pdf;
}
