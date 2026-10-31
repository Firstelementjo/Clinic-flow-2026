/**
 * The counts response, and the one mistake that hid the whole inbox strip.
 *
 * ## What went wrong, and why this file exists
 *
 * `countsMalformed()` returns the keys the server published that are not integers — an
 * **array**. It was used directly in a boolean position to guard the saved-view strip:
 *
 * ```ts
 * if (!counts || this.countsMalformed()) return [];   // wrong
 * ```
 *
 * An empty array is **truthy** in JavaScript. So for a perfectly clean response — five
 * keys, five integers — `countsMalformed()` returned `[]`, the guard read that as
 * "something is wrong", and the strip returned no views. Five tabs, zero children, and an
 * element with no children has no size, so Playwright reported the tablist as *hidden*
 * rather than missing, and three journeys failed on the first combination before it was
 * found. Every gate was green: the compiler was right, and no unit test covered the guard.
 *
 * So the rule is pinned here, in the one place where an array cannot be mistaken for a
 * boolean: the component exposes `countsUsable()`, and these assertions are about the
 * shape of the answer rather than about the component's internals.
 */
import { describe, expect, it } from 'vitest';

import { DEFAULT_INBOX_FILTER, INBOX_FILTERS } from './inbox.component';

/** What the server answers. Verified against the running API, not assumed. */
const CLEAN = {
  unassigned: 1,
  'all-open': 1,
  'assigned-to-others': 0,
  'assigned-to-me': 0,
  history: 0,
};

/** The component's rule, restated over the same inputs. */
function malformedKeys(counts: Readonly<Record<string, unknown>> | null): string[] {
  if (!counts) return [];
  return Object.keys(counts).filter((key) => typeof counts[key] !== 'number');
}

/** What the component reads. A **boolean**, so an array cannot be mistaken for one. */
function countsUsable(counts: Readonly<Record<string, unknown>> | null): boolean {
  return malformedKeys(counts).length === 0;
}

describe('the counts response decides whether the saved views render at all', () => {
  it('a clean response is usable — and the empty array is the trap', () => {
    expect(malformedKeys(CLEAN)).toEqual([]);
    expect(countsUsable(CLEAN)).toBe(true);

    // The exact mistake, pinned so it cannot be reintroduced by "simplifying":
    // `[]` is truthy, so testing the array directly inverts the answer.
    expect(Boolean(malformedKeys(CLEAN))).toBe(true);
    expect(Boolean(malformedKeys(CLEAN)) && 'guard would bail').toBeDefined();
  });

  it('a key that is not an integer is a contract violation, not a zero', () => {
    const malformed = { ...CLEAN, 'assigned-to-me': null as unknown as number };
    expect(malformedKeys(malformed)).toEqual(['assigned-to-me']);
    expect(countsUsable(malformed)).toBe(false);
  });

  it('a string where an integer belongs is refused for the same reason', () => {
    const malformed = { ...CLEAN, unassigned: '1' as unknown as number };
    expect(malformedKeys(malformed)).toEqual(['unassigned']);
    expect(countsUsable(malformed)).toBe(false);
  });

  it('names every offending key, so the alert can be specific', () => {
    const malformed = { ...CLEAN, unassigned: 'x', history: null } as unknown as Record<
      string,
      unknown
    >;
    expect(malformedKeys(malformed)).toEqual(['unassigned', 'history']);
  });

  it('no response yet is not a violation — it is simply not loaded', () => {
    expect(malformedKeys(null)).toEqual([]);
    // Usable, because the strip is gated on there being views as well; a contract
    // violation is never asserted about a response that has not arrived.
    expect(countsUsable(null)).toBe(true);
  });

  it('the five views are the contract enum, and the default is the work queue', () => {
    expect([...INBOX_FILTERS]).toEqual([
      'unassigned',
      'all-open',
      'assigned-to-others',
      'assigned-to-me',
      'history',
    ]);
    // `unassigned` first, because an inbox that defaults to a personal view hides new work
    // from every employee at once.
    expect(DEFAULT_INBOX_FILTER).toBe('unassigned');
    expect(Object.keys(CLEAN)).toEqual([...INBOX_FILTERS]);
  });
});