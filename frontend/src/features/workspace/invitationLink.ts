// 邀请链接识别与"已处理"记录：剪贴板检测弹窗用它去重，生成链接的管理员也借此避免被自己的链接打扰。
const HANDLED_KEY = 'kwiki.invite.handled';
const HANDLED_LIMIT = 50;
const INVITE_PATTERN = /#\/invite\/([A-Za-z0-9%_-]{20,})/;

export function parseInviteToken(text: string | null | undefined): string | null {
  const match = text?.trim().match(INVITE_PATTERN);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return match[1]; }
}

// 只存 token 的非加密摘要，避免把可直接使用的邀请凭证明文留在 localStorage
function fingerprint(token: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < token.length; i += 1) { hash ^= token.charCodeAt(i); hash = Math.imul(hash, 0x01000193); }
  return `${(hash >>> 0).toString(36)}.${token.length}`;
}

function readHandled(): string[] {
  try { const value = JSON.parse(localStorage.getItem(HANDLED_KEY) || '[]'); return Array.isArray(value) ? value : []; } catch { return []; }
}

export function isInviteHandled(token: string): boolean { return readHandled().includes(fingerprint(token)); }

export function markInviteHandled(token: string): void {
  const value = fingerprint(token);
  const next = [value, ...readHandled().filter((item) => item !== value)].slice(0, HANDLED_LIMIT);
  try { localStorage.setItem(HANDLED_KEY, JSON.stringify(next)); } catch { /* 存储不可用时仅失去去重能力 */ }
}
