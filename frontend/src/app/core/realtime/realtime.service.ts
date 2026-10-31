/**
 * Realtime: a refresh channel, never a source of truth (ADR-0003, hard rule 6).
 *
 * Four properties this implementation exists to guarantee, each of them a
 * requirement rather than a nicety:
 *
 * 1. **Frames are hints.** `RealtimeEvent.toFrame()` carries ids, a type, a
 *    payload version and a timestamp — never a message body, never a customer
 *    name. Every frame therefore produces *a refetch*, and the payload is
 *    treated as optional at every call site, because a bridged frame from
 *    another instance may carry ids only (`RedisRealtimeBridge`) and a payload
 *    shape bump must not be a client break.
 * 2. **Resync on every connection.** The server sends `resync` as the first frame
 *    on every connection *including a reconnect*
 *    (`RealtimeStreamService.open`), because after a network blip the client's
 *    state is stale by an unknown amount. The client honours that frame and also
 *    resyncs on its own reconnect, so the behaviour does not depend on the frame
 *    arriving.
 * 3. **Bounded exponential backoff.** The browser's own `EventSource` retry is
 *    not used: it reconnects on a fixed schedule with no ceiling, and a stopped
 *    backend would be hammered forever. Reconnection is explicit, capped, and
 *    jittered so a fleet of open tabs does not stampede.
 * 4. **Staleness is visible.** While the stream is down the shell shows that the
 *    view may be out of date, because an operator acting on a queue they cannot
 *    see change is the failure this indicator exists to prevent.
 *
 * `EventSource` is used rather than a WebSocket because every flow is
 * server→client and the browser's own reconnect plus the shared cookie auth and
 * CORS setup make it the smaller surface. It cannot set headers — which is
 * precisely why this API authenticates with a cookie instead of a bearer token.
 */
import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';
import { environment } from '../../../environments/environment';
import { ApiService } from '../api/api.service';

export type RealtimeStatus = 'stopped' | 'connecting' | 'connected' | 'reconnecting' | 'failed';

/** The frame vocabulary the server declares (`RealtimeEventType`). */
export type RealtimeFrameType =
  | 'conversation'
  | 'message'
  | 'customer'
  | 'notification'
  | 'resync'
  | 'heartbeat'
  | (string & {});

/** The wire frame. Every field beyond `type` is optional by design. */
export interface RealtimeFrame {
  readonly type?: RealtimeFrameType;
  readonly version?: number;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly userId?: string;
  readonly resourceId?: string;
  readonly occurredAt?: string;
  readonly payload?: Readonly<Record<string, unknown>>;
}

/** What a frame means to the client: "refetch this". Never "here is the row". */
export type RealtimeSignalKind =
  | 'resync'
  | 'notifications'
  | 'conversations'
  | 'customers'
  | 'heartbeat';

export interface RealtimeSignal {
  readonly kind: RealtimeSignalKind;
  readonly frame?: RealtimeFrame;
  readonly at: number;
  /** True when the signal came from a reconnect rather than from a live frame. */
  readonly afterReconnect: boolean;
}

const RESYNC_ENDPOINT = '/api/v1/realtime/state';
const STREAM_ENDPOINT = '/api/v1/realtime/stream';

const BACKOFF_BASE_MS = 1_000;
const BACKOFF_MAX_MS = environment.realtimeMaxBackoffMs;

@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly api = inject(ApiService);
  private readonly document = inject(DOCUMENT);

  private source: EventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;
  private wanted = false;
  /** When the last frame of any kind arrived. Proves the path is alive. */
  private lastFrameAt = 0;

  private readonly statusSignal = signal<RealtimeStatus>('stopped');
  private readonly lastSignalSignal = signal<RealtimeSignal | null>(null);
  private readonly frameCountSignal = signal(0);
  private readonly listeners = new Set<(signal: RealtimeSignal) => void>();

  readonly status = this.statusSignal.asReadonly();
  readonly lastSignal = this.lastSignalSignal.asReadonly();
  readonly frameCount = this.frameCountSignal.asReadonly();
  /** Milliseconds since the last frame, or `null` before the first one. */
  readonly lastFrameAgeMs = computed(() =>
    this.lastFrameAt === 0 ? null : Date.now() - this.lastFrameAt,
  );
  readonly isStale = computed(
    () => this.statusSignal() === 'reconnecting' || this.statusSignal() === 'failed',
  );

  /**
   * Opens the stream and keeps it open until `stop()`.
   *
   * `topics` are *requests*: the server computes the allowed set from the
   * resolved `TenantContext` and refuses anything else, so a client cannot widen
   * its subscription by naming a topic. `undefined` subscribes to nothing extra
   * and receives the organization stream plus resync and heartbeats.
   */
  start(topics?: readonly string[]): void {
    if (this.wanted) return;
    this.wanted = true;
    this.open(topics);
  }

  stop(): void {
    this.wanted = false;
    this.cancelTimer();
    this.closeSource();
    this.attempt = 0;
    this.lastFrameAt = 0;
    this.statusSignal.set('stopped');
  }

  /**
   * Subscribes to refresh signals. Returns an unsubscribe function; the store
   * holds one closure per screen rather than a global subject, so a destroyed
   * component cannot keep the stream alive or miss a resync.
   */
  onSignal(listener: (signal: RealtimeSignal) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * The resync target the `resync` frame points at.
   *
   * Concrete on purpose: "resync from the API" is a requirement, and a
   * requirement needs an endpoint. It returns the unread count, the notification
   * list, the server clock and the realtime health, which is enough to restore a
   * tab.
   */
  async resync(): Promise<unknown> {
    return this.api.get(RESYNC_ENDPOINT);
  }

  // -- internals -----------------------------------------------------------

  private open(topics: readonly string[] | undefined): void {
    if (typeof this.document.defaultView?.EventSource === 'undefined') return;
    this.statusSignal.set(this.attempt === 0 ? 'connecting' : 'reconnecting');

    const query = (topics ?? [])
      .filter((topic) => topic !== '')
      .map((topic) => `topic=${encodeURIComponent(topic)}`)
      .join('&');
    const url = `${this.api.baseUrl}${STREAM_ENDPOINT}${query ? `?${query}` : ''}`;

    const source = new this.document.defaultView.EventSource(url, { withCredentials: true });
    this.source = source;

    // Named events: the server sends `event: <type>`, so `onmessage` never fires
    // for them. `addEventListener` per vocabulary entry is the only correct way
    // to read an SSE stream that names its events.
    for (const type of ['conversation', 'message', 'customer', 'notification', 'resync', 'heartbeat']) {
      source.addEventListener(type, (event) => this.handle(type, event as MessageEvent<string>));
    }

    source.onopen = () => {
      this.attempt = 0;
      this.statusSignal.set('connected');
    };

    source.onerror = () => {
      if (!this.wanted) return;
      this.closeSource();
      this.scheduleReconnect(topics);
    };
  }

  private handle(type: string, event: MessageEvent<string>): void {
    this.frameCountSignal.update((count) => count + 1);
    this.lastFrameAt = Date.now();

    const frame = parseFrame(event.data);
    const kind = signalKind(type);
    if (!kind) return;

    const afterReconnect = this.attempt > 0;
    this.attempt = 0;
    this.statusSignal.set('connected');
    this.emit({ kind, frame, at: Date.now(), afterReconnect });
  }

  private scheduleReconnect(topics: readonly string[] | undefined): void {
    this.attempt += 1;
    if (this.attempt > 12) {
      // Give up loudly rather than reconnecting forever against a backend that
      // is not coming back. The shell shows the stale indicator and the user can
      // resync by hand.
      this.statusSignal.set('failed');
      return;
    }
    const ceiling = Math.min(BACKOFF_BASE_MS * 2 ** (this.attempt - 1), BACKOFF_MAX_MS);
    // Jitter, so N open tabs do not all retry on the same tick.
    const delay = Math.round(ceiling / 2 + Math.random() * (ceiling / 2));
    this.statusSignal.set('reconnecting');
    this.cancelTimer();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.wanted) return;
      this.open(topics);
      // A reconnect means an unknown amount of missed frames: resync whether or
      // not the `resync` frame arrives, so recovery does not depend on the wire.
      this.emit({
        kind: 'resync',
        at: Date.now(),
        afterReconnect: true,
        frame: { type: 'resync', payload: { reason: 'client-reconnect' } },
      });
    }, delay);
  }

  private emit(signal: RealtimeSignal): void {
    this.lastSignalSignal.set(signal);
    for (const listener of this.listeners) {
      try {
        listener(signal);
      } catch {
        // One broken subscriber must not stop the others from resyncing.
      }
    }
  }

  private closeSource(): void {
    if (!this.source) return;
    this.source.onopen = null;
    this.source.onerror = null;
    try {
      this.source.close();
    } catch {
      // Already closed.
    }
    this.source = null;
  }

  private cancelTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

/**
 * Reads a frame without trusting it.
 *
 * A frame whose data is not JSON, or whose `type` is unknown, must not take the
 * client down: the payload is a hint and the worst outcome of a bad hint is a
 * missed refresh, which the next resync repairs.
 */
export function parseFrame(raw: string | null | undefined): RealtimeFrame | undefined {
  if (!raw) return undefined;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return undefined;
    const frame = parsed as Record<string, unknown>;
    return {
      type: typeof frame['type'] === 'string' ? frame['type'] : undefined,
      version: typeof frame['version'] === 'number' ? frame['version'] : undefined,
      organizationId:
        typeof frame['organizationId'] === 'string' ? frame['organizationId'] : undefined,
      projectId: typeof frame['projectId'] === 'string' ? frame['projectId'] : undefined,
      userId: typeof frame['userId'] === 'string' ? frame['userId'] : undefined,
      resourceId: typeof frame['resourceId'] === 'string' ? frame['resourceId'] : undefined,
      occurredAt: typeof frame['occurredAt'] === 'string' ? frame['occurredAt'] : undefined,
      payload:
        typeof frame['payload'] === 'object' && frame['payload'] !== null
          ? (frame['payload'] as Record<string, unknown>)
          : undefined,
    };
  } catch {
    return undefined;
  }
}

/** Maps a server event name to the refetch it implies. */
export function signalKind(type: string): RealtimeSignalKind | null {
  switch (type) {
    case 'resync':
      return 'resync';
    case 'notification':
      return 'notifications';
    case 'conversation':
    case 'message':
      return 'conversations';
    case 'customer':
      return 'customers';
    case 'heartbeat':
      return 'heartbeat';
    default:
      return null;
  }
}
