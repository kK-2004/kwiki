/**
 * PDF/DOCX 源文件的浏览器端渲染适配器。
 * 两个适配器都按需动态加载（独立分包），返回带 destroy()
 * 的句柄：调用方在切换页签/页面或卸载时取消渲染并释放资源。
 */
export interface SourceRenderHandle {
  destroy(): void;
}

export interface SourceRenderOptions {
  signal?: AbortSignal;
  httpHeaders?: Record<string, string>;
}

/** 与后端 kwiki.source-preview.max-bytes 对齐的客户端预检上限。 */
export const SOURCE_PREVIEW_MAX_BYTES = 20 * 1024 * 1024;

export type SourceFormat = 'PDF' | 'DOCX';

export async function renderSource(
  container: HTMLElement,
  format: SourceFormat,
  source: Blob | string,
  options: SourceRenderOptions = {},
): Promise<SourceRenderHandle> {
  if (source instanceof Blob && source.size > SOURCE_PREVIEW_MAX_BYTES) {
    throw new Error('文件过大，无法在浏览器中预览');
  }
  if (format === 'PDF') throw new Error('PDF 预览应使用 PdfSourceViewer');
  if (!(source instanceof Blob)) throw new Error('DOCX 预览需要文件字节');
  return renderDocx(container, source, options);
}

async function renderDocx(container: HTMLElement, blob: Blob, options: SourceRenderOptions): Promise<SourceRenderHandle> {
  const { renderAsync } = await import('docx-preview');
  await renderAsync(blob, container, undefined, {
    className: 'kwiki-docx',
    inWrapper: true,
    ignoreWidth: false,
    ignoreHeight: false,
    ignoreLastRenderedPageBreak: true,
    experimental: true,
    breakPages: true,
    ignoreFonts: false,
    useBase64URL: true,
  });
  if (options.signal?.aborted) {
    container.replaceChildren();
    throw new Error('预览已取消');
  }
  return {
    destroy() { container.replaceChildren(); },
  };
}
