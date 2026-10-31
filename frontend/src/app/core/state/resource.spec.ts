/**
 * The async-state helper: loading, empty, error, stale.
 *
 * These are the four states phase-11 hard rule 8 requires every screen to design
 * rather than improvise, and the ways they get confused are all tested here.
 */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/api.service';
import { ResourceStore, defaultIsEmpty } from './resource';

/**
 * Builds a store inside an injection context.
 *
 * `ResourceStore` is deliberately not `providedIn: 'root'`: it is a per-screen
 * piece of state, and a generic type parameter cannot come from an injector.
 * Components construct one as a field, which is an injection context, and this
 * helper is the same thing for a test.
 */
function store<T>(): ResourceStore<T> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  return TestBed.runInInjectionContext(() => new ResourceStore<T>());
}

describe('defaultIsEmpty', () => {
  it('treats an empty array as empty', () => {
    expect(defaultIsEmpty([])).toBe(true);
    expect(defaultIsEmpty([1])).toBe(false);
  });

  it('reads a cursor page and an unassigned page', () => {
    expect(defaultIsEmpty({ items: [], nextCursor: null })).toBe(true);
    expect(defaultIsEmpty({ items: [{ id: 'a' }] })).toBe(false);
    expect(defaultIsEmpty({ entries: [], totals: {} })).toBe(true);
    expect(defaultIsEmpty({ entries: [{ conversation: {} }] })).toBe(false);
  });

  it('does not call a counters object empty', () => {
    // A page that reports `dueNow: 0` is showing values, not an absence. FR-7.6's
    // "no data" versus zero distinction applies to operations as much as finance.
    expect(defaultIsEmpty({ dueNow: 0, total: 0 })).toBe(false);
  });

  it('treats null and undefined as empty', () => {
    expect(defaultIsEmpty(null)).toBe(true);
    expect(defaultIsEmpty(undefined)).toBe(true);
  });
});

describe('ResourceStore', () => {
  it('is loading before the first response and ready after it', async () => {
    const resource = store<{ items: number[] }>();
    expect(resource.loading()).toBe(true);
    const pending = resource.load(async () => ({ items: [1] }));
    expect(resource.loading()).toBe(true);
    await pending;
    expect(resource.loading()).toBe(false);
    expect(resource.phase()).toBe('ready');
    expect(resource.empty()).toBe(false);
    expect(resource.error()).toBeNull();
    expect(resource.loadedAt()).toBeGreaterThan(0);
  });

  it('reports empty only once a response has actually arrived', async () => {
    const resource = store<{ items: number[] }>();
    expect(resource.empty()).toBe(false);
    await resource.load(async () => ({ items: [] }));
    expect(resource.empty()).toBe(true);
  });

  it('surfaces a failure as an ApiError with the correlation id', async () => {
    const resource = store<{ items: number[] }>();
    await resource.load(async () => {
      throw new ApiError(403, 'ACCESS_DENIED', 'nope', 'corr-7', null);
    });
    expect(resource.phase()).toBe('error');
    expect(resource.error()?.requestId).toBe('corr-7');
    expect(resource.empty()).toBe(false);
    expect(resource.stale()).toBe(false);
  });

  it('keeps the previous value visible while reloading', async () => {
    const resource = store<{ items: number[] }>();
    await resource.load(async () => ({ items: [1] }));
    const pending = resource.refresh();
    expect(resource.value()).toEqual({ items: [1] });
    expect(resource.reloading()).toBe(true);
    await pending;
    expect(resource.reloading()).toBe(false);
  });

  it('keeps the previous value and says it is stale when a refresh fails', async () => {
    // Replacing good data with an error card is the failure mode here: the
    // operator loses the queue they were reading because one refresh failed.
    const resource = store<{ items: number[] }>();
    let shouldFail = false;
    await resource.load(async () => {
      if (shouldFail) throw new ApiError(500, 'INTERNAL', 'boom', 'corr-9', null);
      return { items: [1] };
    });
    shouldFail = true;
    await resource.refresh();

    expect(resource.value()).toEqual({ items: [1] });
    expect(resource.error()).not.toBeNull();
    expect(resource.stale()).toBe(true);
    expect(resource.staleReason()).toBe('refresh-failed');
  });

  it('does not let a superseded response overwrite a newer one', async () => {
    const resource = store<{ items: number[] }>();
    let releaseFirst: (() => void) | undefined;
    const slow = resource.load(
      () =>
        new Promise<{ items: number[] }>((resolve) => {
          releaseFirst = () => resolve({ items: [1] });
        }),
    );
    const fast = resource.load(async () => ({ items: [2] }));
    await fast;
    releaseFirst?.();
    await slow;
    // The slow first request must not win: it was superseded.
    expect(resource.value()).toEqual({ items: [2] });
  });

  it('marks a loaded value stale without discarding it', async () => {
    const resource = store<{ items: number[] }>();
    await resource.load(async () => ({ items: [1] }));
    resource.markStale('realtime-down');
    expect(resource.stale()).toBe(true);
    expect(resource.value()).toEqual({ items: [1] });
    resource.clearStale();
    expect(resource.stale()).toBe(false);
  });

  it('does not mark anything stale before there is a value to be stale', async () => {
    const resource = store<{ items: number[] }>();
    resource.markStale('realtime-down');
    expect(resource.stale()).toBe(false);
  });

  it('accepts a value applied directly, for a refetch whose response is already in hand', () => {
    const resource = store<{ items: number[] }>();
    resource.set({ items: [3] });
    expect(resource.phase()).toBe('ready');
    expect(resource.empty()).toBe(false);
    expect(resource.stale()).toBe(false);
  });

  it('resets to idle', async () => {
    const resource = store<{ items: number[] }>();
    await resource.load(async () => ({ items: [1] }));
    resource.reset();
    expect(resource.value()).toBeNull();
    expect(resource.loading()).toBe(true);
  });
});
