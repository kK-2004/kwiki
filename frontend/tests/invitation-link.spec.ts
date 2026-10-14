import { describe, expect, it } from 'vitest';
import { isInviteHandled, markInviteHandled, parseInviteToken } from '../src/features/workspace/invitationLink';

const token = '0f8b5c1e-2d4a-4b7f-9c3e-1a2b3c4d5e6f0123456789abcdef0123456789abcdef';

describe('invitation link detection', () => {
  it('extracts the token from a copied invite link', () => {
    expect(parseInviteToken(`  https://wiki.example.com/#/invite/${token}\n`)).toBe(token);
    expect(parseInviteToken(`http://localhost:5173/index.html#/invite/${encodeURIComponent(token)}`)).toBe(token);
  });

  it('ignores unrelated clipboard text', () => {
    expect(parseInviteToken('https://wiki.example.com/#/transfer/abcdefghijklmnopqrstuvwxyz')).toBeNull();
    expect(parseInviteToken('#/invite/short')).toBeNull();
    expect(parseInviteToken(null)).toBeNull();
  });

  it('remembers handled tokens without storing the raw token', () => {
    expect(isInviteHandled(token)).toBe(false);
    markInviteHandled(token);
    expect(isInviteHandled(token)).toBe(true);
    expect(localStorage.getItem('kwiki.invite.handled')).not.toContain(token);
  });
});
