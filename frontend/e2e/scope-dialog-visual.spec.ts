import { expect, test, type Page, type Route } from '@playwright/test';
import { FAKE_TOKEN, envelope } from './fixtures';

/** 临时视觉验证：以设计稿同款数据结构打开“选择检索范围”弹窗并截图。 */

async function openScopeDialog(page: Page) {
  await page.addInitScript(token => sessionStorage.setItem('kwiki.auth.token', token), FAKE_TOKEN);
  await page.route('**/api/v1/**', async (route: Route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const json = (data: unknown) => route.fulfill({ json: envelope(data) });
    if (path === '/auth/me') return json({ id: 7, username: 'e2e-user', displayName: '端到端用户', admin: false });
    if (path === '/recent-visits') return json([]);
    if (path === '/notifications/unread-count') return json(0);
    if (path === '/knowledge-bases') {
      return json([
        { id: 1, uuid: 'kb-1', name: 'test' },
        { id: 2, uuid: 'kb-2', name: '产品手册 · 联调验收' },
      ]);
    }
    if (path === '/knowledge-bases/1/tree') {
      return json([
        { id: 11, uuid: 'n11', title: 'API 说明', nodeType: 'PAGE', parentId: null, children: [] },
        { id: 12, uuid: 'n12', title: 'FAQ', nodeType: 'PAGE', parentId: null, children: [] },
      ]);
    }
    if (path === '/knowledge-bases/2/tree') {
      return json([
        { id: 21, uuid: 'n21', title: 'qa', nodeType: 'PAGE', parentId: null, children: [] },
        {
          id: 22, uuid: 'n22', title: '使用指南', nodeType: 'FOLDER', parentId: null,
          children: [
            { id: 221, uuid: 'n221', title: '快速开始', nodeType: 'PAGE', parentId: 22, children: [] },
            { id: 222, uuid: 'n222', title: '权限说明', nodeType: 'PAGE', parentId: 22, children: [] },
            { id: 223, uuid: 'n223', title: '含图片与表格的大文档', nodeType: 'PAGE', parentId: 22, children: [] },
          ],
        },
        {
          id: 23, uuid: 'n23', title: '安装部署', nodeType: 'FOLDER', parentId: null,
          children: [
            { id: 231, uuid: 'n231', title: '安装说明', nodeType: 'PAGE', parentId: 23, children: [] },
            { id: 232, uuid: 'n232', title: '部署说明', nodeType: 'PAGE', parentId: 23, children: [] },
          ],
        },
      ]);
    }
    if (path === '/chat/sessions') return json([]);
    return json(undefined);
  });
  await page.goto('/#/conversations');
  await page.getByRole('button', { name: '可访问的知识库' }).click();
  await expect(page.getByRole('dialog', { name: '选择检索范围' })).toBeVisible();
}

test('scope dialog desktop layout', async ({ page }) => {
  await openScopeDialog(page);
  // 切到第二个知识库并全选其使用指南文件夹（半选父级的典型场景）。
  await page.getByRole('button', { name: '产品手册 · 联调验收' }).click();
  await page.getByRole('checkbox', { name: '全选文件夹 使用指南' }).click();
  await page.getByRole('checkbox', { name: '全选文件夹 安装部署' }).click();
  await page.screenshot({ path: 'test-results/scope-desktop.png' });
});

test('scope dialog mobile tabs', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openScopeDialog(page);
  await page.getByRole('checkbox', { name: '整库选择 产品手册 · 联调验收' }).click();
  await page.getByRole('tab', { name: '已选择' }).or(page.getByRole('button', { name: '已选择' })).click();
  await page.screenshot({ path: 'test-results/scope-mobile.png' });
});

test('scope dialog footer stays fixed when selection content changes', async ({ page }) => {
  await openScopeDialog(page);
  const footer = page.locator('.scope-footer');
  const initial = await footer.boundingBox();
  expect(initial).not.toBeNull();
  // 页脚只应包含它自己的按钮和内边距，即使内容很短也是如此。
  expect(initial!.height).toBeLessThan(80);
  await page.getByRole('checkbox', { name: '整库选择 产品手册 · 联调验收' }).click();
  await expect(page.getByText('整个知识库', { exact: true })).toBeVisible();
  expect(await footer.boundingBox()).toEqual(initial);
  await page.getByRole('button', { name: '取消整库选择' }).click();
  await expect(page.getByText('请选择需要检索的内容', { exact: true })).toBeVisible();
  expect(await footer.boundingBox()).toEqual(initial);
  // 内容较长时必须在自己的面板内滚动，不能挤动页脚。
  await page.getByRole('button', { name: '产品手册 · 联调验收' }).click();
  const content = page.locator('.scope-columns > section').nth(1);
  const bounds = await content.boundingBox();
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(initial!.y + 1);
  expect(await content.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  await content.evaluate(el => { el.scrollTop = el.scrollHeight; });
  await expect(page.getByRole('button', { name: '部署说明', exact: true })).toBeInViewport();
  expect(await footer.boundingBox()).toEqual(initial);
});
