import { test, expect } from '@playwright/test';
import { installAuth, mockWorkspaceApi } from './fixtures';

test.describe('参考列表到正文高亮', () => {
  test('引用跳转定位到可见高亮并消费 chunk 参数', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.goto('/#/knowledge-bases/1/3?chunk=C%2F1');
    const notice = page.getByRole('status').filter({ hasText: '未能精确定位' });
    await expect(notice).toHaveCount(0);
    // Custom Highlight 注册表存在命中条目（真实 Highlight 不暴露 ranges 属性，
    // 可见样式由 CSS ::highlight(kwiki-citation) 提供，mark 回退用例单独验证文本）。
    await expect.poll(() => page.evaluate(() =>
      Boolean((globalThis as { CSS?: { highlights?: Map<string, unknown> } }).CSS?.highlights?.get('kwiki-citation'))
    )).toBe(true);
    // 路由参数只在最终结果后消费。
    await expect(page).toHaveURL(/\/knowledge-bases\/1\/3$/);
  });

  test('无 Custom Highlight API 时使用临时 mark 回退并按生命周期清理', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.addInitScript(() => {
      Object.defineProperty(globalThis, 'Highlight', { configurable: true, get: () => undefined });
    });
    await page.goto('/#/knowledge-bases/1/3?chunk=C%2F1');
    const mark = page.locator('mark[data-kwiki-citation]');
    await expect(mark).toHaveCount(1);
    await expect(mark).toHaveText('目标命中段落内容');
    // 高亮是临时的：超时后正文结构无损还原。
    await expect(mark).toHaveCount(0, { timeout: 6000 });
    await expect(page.getByTestId('page-content')).toContainText('目标命中段落内容');
  });

  test('失效引用展示不可访问状态且不高亮任何文本', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    // 覆盖路由需在通用 mock 之后注册才能优先生效。
    await page.route('**/api/v1/citations/**', route =>
      route.fulfill({ status: 404, json: { code: 404, success: false, message: 'not found' } }));
    await page.goto('/#/knowledge-bases/1/3?chunk=C%2F1');
    await expect(page.getByText('原片段已失效或无权访问，无法定位。')).toBeVisible();
    const highlighted = await page.evaluate(() => {
      const highlights = (globalThis as unknown as { CSS?: { highlights?: Map<string, unknown> } }).CSS?.highlights;
      return highlights?.has('kwiki-citation') ?? false;
    });
    expect(highlighted).toBe(false);
  });
});

test.describe('PDF/DOCX 来源页签与水印', () => {
  test('PDF 导入页展示源文件页签、受保护预览与水印', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {
      sourceDocument: { format: 'PDF', fileName: 'deploy-manual.pdf', byteSize: 2048, attachmentUuid: 'att-1' },
    });
    await page.goto('/#/knowledge-bases/1/3');
    await expect(page.getByRole('tab', { name: '解析文本' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'PDF 源文件' })).toBeVisible();
    await expect(page.getByTestId('page-content')).toBeVisible();

    await page.getByRole('tab', { name: 'PDF 源文件' }).click();
    // 同源读取后由 PDF.js 在页面中绘制。
    await expect(page.locator('.source-pdf-page')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('.thumb-item[aria-current="page"]')).toHaveAttribute('aria-label', '第 1 页');
    const watermark = page.getByTestId('source-watermark');
    await expect(watermark).toBeVisible();
    await expect(watermark).toContainText('e2e-user');
    // 滚动后水印仍覆盖可视区域。
    await page.locator('.viewer').evaluate(el => { el.scrollTop = 400; });
    await expect(watermark).toBeVisible();

    // 切回解析文本：阅读内容保留，预览资源释放。
    const content = page.getByTestId('page-content');
    await page.getByRole('tab', { name: '解析文本' }).click();
    await expect(content).toBeVisible();
    await expect(content).toContainText('目标命中段落内容');
    await expect(page.getByTestId('source-preview')).toHaveCount(0);
  });

  test('多页 PDF 页面内渲染且下载响应头不会触发下载', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {
      sourceDocument: { format: 'PDF', fileName: 'long.pdf', byteSize: 4096, attachmentUuid: 'att-1' },
    });
    await page.goto('/#/knowledge-bases/1/3');
    const downloads: string[] = [];
    page.on('download', download => downloads.push(download.suggestedFilename()));
    await page.getByRole('tab', { name: 'PDF 源文件' }).click();
    await expect(page.locator('.source-pdf-page')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('.thumb-item')).toHaveCount(3);
    await expect(page.locator('.thumb-item[aria-current="page"]')).toHaveAttribute('aria-label', '第 1 页');
    await expect.poll(() => page.locator('.source-pdf-page').evaluate(node => {
      const canvas = node as HTMLCanvasElement;
      return canvas.getContext('2d')!.getImageData(0, 0, 1, 1).data[3];
    })).toBe(255);
    await page.getByRole('button', { name: '下一页' }).click();
    await expect(page.locator('.thumb-item[aria-current="page"]')).toHaveAttribute('aria-label', '第 2 页');
    expect(downloads).toEqual([]);
  });

  test('DOCX 导入页预览原文件并叠加水印', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {
      sourceDocument: {
        format: 'DOCX', fileName: 'handbook.docx', byteSize: 1139, attachmentUuid: 'att-1',
      },
    });
    await page.goto('/#/knowledge-bases/1/3');
    await page.getByRole('tab', { name: 'DOCX 源文件' }).click();
    await expect(page.getByTestId('source-preview')).toBeVisible();
    await expect(page.locator('.kwiki-docx-wrapper')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('.kwiki-docx-wrapper')).toContainText('kwiki e2e docx sample paragraph');
    await expect(page.getByTestId('source-watermark')).toContainText('e2e-user');
  });

  test('普通页面不显示来源页签', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.goto('/#/knowledge-bases/1/3');
    // 中栏“目录/摘要”是应用自身的 tablist：这里只断言不出现来源页签。
    await expect(page.getByRole('tab', { name: /源文件/ })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '解析文本' })).toHaveCount(0);
    await expect(page.getByTestId('page-content')).toBeVisible();
  });

  test('预览无权访问时显示不可访问状态', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {
      sourceDocument: { format: 'PDF', fileName: 'revoked.pdf', byteSize: 512, attachmentUuid: 'att-1' },
    });
    await page.route('**/api/v1/knowledge-bases/1/pages/3/source-preview', route =>
      route.fulfill({ status: 403, json: { code: 403, success: false, message: 'forbidden' } }));
    await page.goto('/#/knowledge-bases/1/3');
    await page.getByRole('tab', { name: 'PDF 源文件' }).click();
    await expect(page.getByTestId('source-error')).toContainText('无权');
    await expect(page.getByTestId('source-retry')).toHaveCount(0);
  });
});

test.describe('浮窗历史会话续聊', () => {
  test('浮窗内浏览历史、就地选择并展开到相同会话', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.goto('/#/knowledge-bases/1/3');
    await page.getByRole('button', { name: '问问 kwiki' }).click();
    await expect(page.getByRole('dialog', { name: '问问 kwiki' })).toBeVisible();

    await page.getByTestId('float-history').click();
    const panel = page.getByTestId('float-history-panel');
    await expect(panel).toContainText('部署相关问题');
    await expect(panel).toContainText('权限配置问题');

    await page.getByTestId('history-session-s2').click();
    await expect(page.getByTestId('float-history-panel')).toHaveCount(0);
    await expect(page.getByText('权限怎么配？')).toBeVisible();

    // 展开完整页携带相同 sessionId，且不重复发送 turn。
    await page.getByRole('button', { name: '展开完整聊天' }).click();
    await expect(page).toHaveURL(/#\/conversations\/s2$/);
    await expect(page.getByText('权限怎么配？')).toBeVisible();
  });

  test('Escape 先关闭历史面板再最小化浮窗', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.goto('/#/knowledge-bases/1/3');
    await page.getByRole('button', { name: '问问 kwiki' }).click();
    await page.getByTestId('float-history').click();
    await expect(page.getByTestId('float-history-panel')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('float-history-panel')).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: '问问 kwiki' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: '问问 kwiki' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '问问 kwiki' })).toBeVisible();
  });
});

test.describe('流式跟随与思考动画', () => {
  test('消息区跟随底部：内容增长跟随、用户上滚暂停、回底恢复', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.goto('/#/conversations');
    await expect(page.getByLabel('聊天消息')).toBeVisible();
    const area = page.getByLabel('聊天消息');

    // 注入一批长消息：内容高度变化（ResizeObserver）应在 FOLLOWING 状态下拉到底。
    // 与真实流式一致，注入之间留出渲染帧。
    const grow = (label: string) => area.evaluate((el, text) => {
      const list = el.querySelector('.message-list');
      if (!list) throw new Error('message list missing');
      const article = document.createElement('article');
      article.className = 'message assistant';
      article.textContent = text;
      list.append(article);
      return list.children.length;
    }, label);

    for (let index = 0; index < 12; index++) {
      await grow(`历史消息 ${index} `.repeat(12));
      await page.waitForTimeout(40);
    }
    await expect.poll(() => area.evaluate(el =>
      el.scrollHeight - el.scrollTop - el.clientHeight), { timeout: 10_000 }).toBeLessThan(48);

    // 用户上滚：暂停跟随，新内容不得拉回底部。
    await area.hover();
    await page.mouse.wheel(0, -600);
    await expect.poll(() => area.evaluate(el =>
      el.scrollHeight - el.scrollTop - el.clientHeight), { timeout: 10_000 }).toBeGreaterThan(48);
    const pausedAt = await area.evaluate(el => el.scrollTop);
    for (let index = 0; index < 5; index++) {
      await grow(`暂停期间新消息 ${index} `.repeat(12));
      await page.waitForTimeout(40);
    }
    await page.waitForTimeout(300);
    expect(await area.evaluate(el => el.scrollTop)).toBe(pausedAt);

    // 主动回到底部：恢复跟随，后续内容自动贴底。
    await area.evaluate(el => { el.scrollTop = el.scrollHeight; });
    await expect.poll(() => area.evaluate(el =>
      el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(48);
    await grow('恢复跟随后的新内容 '.repeat(12));
    await expect.poll(() => area.evaluate(el =>
      el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(48);
  });

  test('窄视口下浮窗与阅读器可用（键盘可达）', async ({ page }) => {
    await installAuth(page);
    await mockWorkspaceApi(page, {});
    await page.setViewportSize({ width: 390, height: 780 });
    await page.goto('/#/knowledge-bases/1/3');
    await expect(page.getByTestId('page-content')).toBeVisible();
    await page.getByRole('button', { name: '问问 kwiki' }).click();
    await expect(page.getByRole('dialog', { name: '问问 kwiki' })).toBeVisible();
    await page.getByTestId('float-new-conversation').focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog', { name: '问问 kwiki' })).toBeVisible();
    await expect(page.getByText('有什么可以帮你？')).toBeVisible();
  });
});
