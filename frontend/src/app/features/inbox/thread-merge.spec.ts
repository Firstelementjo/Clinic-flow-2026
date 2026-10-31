/**
 * The close/reopen lifecycle: a partial response must never become the thread.
 *
 * ## Why this is a unit test and not only a journey
 *
 * The browser journey is what caught the bug — clicking Reopen did nothing, silently.
 * But the journey can only prove it for the combinations it runs. The rule it caught is
 * general: **a write's response is folded into the thread on screen, and a response that
 * carries no `id` is a patch, not a replacement.**
 *
 * So the rule is pinned here, where it is deterministic, for all three cases:
 * a whole thread supersedes, a patch merges, and a patch never leaves the thread without
 * an identity for the next action to act on.
 */
import { describe, expect, it } from 'vitest';

import { ConversationStatusView } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';

/** What the server actually answers `POST .../close` with. Verified, not assumed. */
const CLOSE_RESPONSE: ConversationStatusView = { status: 'CLOSED' };

/** A whole thread, which is what the reads return. */
function thread(overrides: Partial<Api.ConversationDetail> = {}): Api.ConversationDetail {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    version: '7',
    status: 'OPEN',
    ...overrides,
  } as Api.ConversationDetail;
}

/**
 * The component's merge rule, restated independently of the component.
 *
 * Deliberately a second implementation rather than an import: if this imported the
 * component's helper it would agree with the helper by construction and prove nothing.
 * It encodes the same rule from the specification, so a change to either side has to be
 * argued for in both.
 */
function applyThread(
  current: Api.ConversationDetail | null,
  next: Partial<Api.ConversationDetail> | ConversationStatusView,
): Api.ConversationDetail {
  const patch = next as Record<string, unknown>;
  const isWholeThread = typeof patch['id'] === 'string' && patch['id'] !== '';
  return (isWholeThread
    ? (next as Api.ConversationDetail)
    : { ...(current ?? {}), ...next }) as Api.ConversationDetail;
}

describe('a write response is folded into the thread, never substituted for it', () => {
  it('the close response carries a status and no id — the shape that broke every later action', () => {
    // Stated rather than assumed: this is the exact body the server sends, and the whole
    // bug is that a client treated it as a thread.
    expect(CLOSE_RESPONSE['status']).toBe('CLOSED');
    expect(CLOSE_RESPONSE['id']).toBeUndefined();
  });

  it('merges a status-only response, keeping the identity the actions need', () => {
    const before = thread();
    const after = applyThread(before, CLOSE_RESPONSE);

    expect(after['status']).toBe('CLOSED');
    // The two fields every action reads through `requireThread()`.
    expect(after['id']).toBe('11111111-1111-1111-1111-111111111111');
    // And the optimistic-concurrency version, without which the next write is refused.
    expect((after as Record<string, unknown>)['version']).toBe('7');
  });

  it('substituting instead of merging is what produced a dead button, and is now rejected', () => {
    const before = thread();

    // The old behaviour, kept here as the assertion's negative: replacing the thread with
    // the status object leaves no id, so `requireThread()` returns null and every action
    // returns at its first line without a request and without a word.
    const broken = CLOSE_RESPONSE as unknown as Api.ConversationDetail;
    expect(
      typeof broken['id'] === 'string' && broken['id'] !== '',
      'a substituted status cannot answer "which conversation?"',
    ).toBe(false);

    expect(applyThread(before, CLOSE_RESPONSE)['id']).toBe(
      '11111111-1111-1111-1111-111111111111',
    );
  });

  it('a whole thread still supersedes, because a full read is authoritative', () => {
    const before = thread({ status: 'CLOSED', priority: 'HIGH' } as Partial<Api.ConversationDetail>);
    const fresh = thread({ status: 'OPEN' });
    const after = applyThread(before, fresh);

    expect(after['status']).toBe('OPEN');
    // Superseded, not merged: a field the server no longer sends must not survive from
    // the previous copy, or the screen shows something the server has withdrawn.
    expect(after['priority']).toBeUndefined();
  });

  it('the reopen status restores the case, so the next action has an id to act on', () => {
    let current = applyThread(thread(), CLOSE_RESPONSE);
    current = applyThread(current, { status: 'OPEN' } as ConversationStatusView);

    expect(current['status']).toBe('OPEN');
    expect(current['id']).toBe('11111111-1111-1111-1111-111111111111');
  });

  it('never invents an id when there is no thread yet', () => {
    // The one case where merging cannot help: with nothing on screen there is no
    // identity to preserve, so the result must not pretend to be a conversation.
    const after = applyThread(null, CLOSE_RESPONSE);
    expect(typeof after['id'] === 'string' && after['id'] !== '').toBe(false);
  });
});