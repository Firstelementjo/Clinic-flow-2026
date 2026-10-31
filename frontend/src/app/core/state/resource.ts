/**
 * A typed async read with the four states the design system requires to be
 * *designed* rather than improvised per screen: loading, empty, error, stale
 * (phase-11 hard rule 8).
 *
 * Why a helper rather than `HttpClient` plus a `loading` boolean:
 *
 * - **"Empty" and "loaded with nothing" are different from "loading" and from
 *   "failed".** A screen that renders a spinner until the first response and then
 *   an empty state has no way to say "here is a count of zero" differently from
 *   "the request is in flight", and those are exactly the two states an operator
 *   misreads.
 * - **Stale is a state, not an afterthought.** A screen loaded while the realtime
 *   stream was down is showing data of unknown age. That has to be sayable, and
 *   `staleReason` is what makes it sayable.
 * - **A reload keeps the previous value visible.** The queue does not blank out
 *   every time a message arrives, and a failed refresh says so instead of
 *   replacing good data with an error card.
 * - **Superseded responses are discarded.** Two loads racing (a realtime frame
 *   arriving while a manual refresh is in flight) must not let the slower one win.
 */
import {
  DestroyRef,
  Injectable,
  Signal,
  computed,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { ApiError, toApiError } from '../api/api.service';

export type ResourcePhase = 'idle' | 'loading' | 'ready' | 'error';

/** Why a value on screen may be out of date. */
export type StaleReason = 'realtime-down' | 'refresh-failed';

export interface ResourceState<T> {
  readonly phase: ResourcePhase;
  readonly value: T | null;
  readonly error: ApiError | null;
  readonly loadedAt: number | null;
  readonly stale: boolean;
  readonly staleReason: StaleReason | null;
}

const INITIAL = {
  phase: 'idle',
  value: null,
  error: null,
  loadedAt: null,
  stale: false,
  staleReason: null,
} as const;

/**
 * What counts as "empty" per shape.
 *
 * A page with `items: []` is genuinely empty. An object with only counters is
 * showing *values*, not an absence, and must not render an empty state — which is
 * why the emptiness test is passed per call rather than guessed here.
 */
export function defaultIsEmpty<T>(value: T): boolean {
  if (value === null || value === undefined) return true;
  if (Array.isArray(value)) return value.length === 0;
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    if (Array.isArray(record['items'])) return (record['items'] as unknown[]).length === 0;
    if (Array.isArray(record['entries'])) return (record['entries'] as unknown[]).length === 0;
  }
  return false;
}

@Injectable()
export class ResourceStore<T> {
  private readonly destroyRef = inject(DestroyRef);
  private readonly current = signal<ResourceState<T>>(INITIAL as ResourceState<T>);
  private loader: (() => Promise<T>) | null = null;
  private emptiness: (value: T) => boolean = defaultIsEmpty;
  private ticket = 0;

  /** The whole state, for a template that wants it in one read. */
  readonly snapshot: Signal<ResourceState<T>> = this.current.asReadonly();
  /** First load in flight with nothing on screen yet. */
  readonly loading = computed(
    () => this.current().phase === 'loading' || this.current().phase === 'idle',
  );
  /** A reload is in flight but a previous value is still on screen. */
  readonly reloading = computed(
    () => this.current().phase === 'loading' && this.current().value !== null,
  );
  readonly value = computed(() => this.current().value);
  readonly phase = computed(() => this.current().phase);
  readonly error = computed(() => this.current().error);
  readonly loadedAt = computed(() => this.current().loadedAt);
  readonly stale = computed(() => this.current().stale);
  readonly staleReason = computed(() => this.current().staleReason);
  readonly empty = computed(() => {
    const state = this.current();
    return state.phase === 'ready' && state.value !== null && this.emptiness(state.value);
  });

  constructor() {
    this.destroyRef.onDestroy(() => {
      // Nothing may write into a destroyed view.
      this.loader = null;
      this.ticket += 1;
    });
  }

  load(loader: () => Promise<T>, isEmpty?: (value: T) => boolean): Promise<T | null> {
    this.emptiness = isEmpty ?? defaultIsEmpty;
    this.loader = loader;
    return this.run(loader);
  }

  /** Reloads with the last loader. Safe when nothing has loaded yet. */
  refresh(): Promise<T | null> {
    if (!this.loader) return Promise.resolve(this.current().value);
    return this.run(this.loader);
  }

  /** Applies a value without a request. */
  set(value: T): void {
    this.current.set({
      phase: 'ready',
      value,
      error: null,
      loadedAt: Date.now(),
      stale: false,
      staleReason: null,
    });
  }

  /** Marks the current value as possibly out of date, without discarding it. */
  markStale(reason: StaleReason): void {
    this.current.update((state) =>
      state.phase === 'ready' && state.value !== null
        ? { ...state, stale: true, staleReason: reason }
        : state,
    );
  }

  clearStale(): void {
    this.current.update((state) => ({ ...state, stale: false, staleReason: null }));
  }

  reset(): void {
    this.current.set(INITIAL as ResourceState<T>);
  }

  private async run(loader: () => Promise<T>): Promise<T | null> {
    const mine = ++this.ticket;
    this.current.update((state) => ({
      phase: 'loading',
      value: state.value,
      error: null,
      loadedAt: state.loadedAt,
      stale: state.stale,
      staleReason: state.staleReason,
    }));

    try {
      const value = await untracked(() => loader());
      if (mine !== this.ticket) return this.current().value;
      this.current.set({
        phase: 'ready',
        value,
        error: null,
        loadedAt: Date.now(),
        stale: false,
        staleReason: null,
      });
      return value;
    } catch (cause) {
      const error = toApiError(cause);
      if (mine !== this.ticket) return this.current().value;
      this.current.update((state) => ({
        phase: 'error',
        value: state.value,
        error,
        loadedAt: state.loadedAt,
        stale: state.value !== null,
        staleReason: state.value !== null ? 'refresh-failed' : null,
      }));
      return null;
    }
  }
}
