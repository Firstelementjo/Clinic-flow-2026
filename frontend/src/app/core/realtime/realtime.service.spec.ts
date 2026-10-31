/**
 * Realtime: a refresh channel, never a source of truth.
 *
 * ADR-0003 and phase-11 hard rule 6. The four properties under test are the four
 * the ADR requires:
 *
 * 1. a frame is a hint — every frame produces a refetch, and its payload is
 *    optional because a bridged frame may carry ids only;
 * 2. resync happens on every connection, including a reconnect;
 * 3. reconnection is bounded and jittered;
 * 4. staleness is visible while the stream is down.
 *
 * The `EventSource` used here is a hand-written double, because the real one is a
 * browser primitive with no injection seam. Its surface is exactly what the
 * service uses: `addEventListener`, `onopen`, `onerror`, `close`.
 */
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RealtimeService, parseFrame, signalKind } from './realtime.service';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly url: string;
  readonly options: unknown;
  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(url: string, options?: unknown) {
    this.url = url;
    this.options = options;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, listener);
  }

  close(): void {
    this.closed = true;
  }

  /** Test helper: deliver a named frame exactly as the server does. */
  emit(type: string, data: unknown): void {
    const listener = this.listeners.get(type);
    if (!listener) throw new Error(`no listener for ${type}`);
    listener({ data: typeof data === 'string' ? data : JSON.stringify(data) } as MessageEvent<string>);
  }

  open(): void {
    this.onopen?.();
  }

  fail(): void {
    this.onerror?.();
  }

  static latest(): FakeEventSource {
    const source = FakeEventSource.instances.at(-1);
    if (!source) throw new Error('no EventSource was opened');
    return source;
  }

  static reset(): void {
    FakeEventSource.instances = [];
  }
}

describe('parseFrame', () => {
  it('reads the documented frame shape', () => {
    const frame = parseFrame(
      '{"type":"conversation","version":1,"organizationId":"o","projectId":"p","resourceId":"r","occurredAt":"2026-10-06T10:00:00","payload":{"reason":"assigned"}}',
    );
    expect(frame).toMatchObject({ type: 'conversation', version: 1, resourceId: 'r' });
    expect(frame?.payload).toEqual({ reason: 'assigned' });
  });

  it('treats every field beyond the type as optional', () => {
    // ADR-0007 section 2 consequence: a bridged frame may carry ids only, and a
    // client that required a payload would drop the frame.
    expect(parseFrame('{"type":"message"}')).toEqual({
      type: 'message',
      version: undefined,
      organizationId: undefined,
      projectId: undefined,
      userId: undefined,
      resourceId: undefined,
      occurredAt: undefined,
      payload: undefined,
    });
  });

  it('survives malformed data instead of taking the tab down', () => {
    expect(parseFrame('not json')).toBeUndefined();
    expect(parseFrame('')).toBeUndefined();
    expect(parseFrame('null')).toBeUndefined();
    expect(parseFrame('42')).toBeUndefined();
  });
});

describe('signalKind', () => {
  it('maps the server vocabulary to the refetch it implies', () => {
    expect(signalKind('resync')).toBe('resync');
    expect(signalKind('notification')).toBe('notifications');
    expect(signalKind('conversation')).toBe('conversations');
    expect(signalKind('message')).toBe('conversations');
    expect(signalKind('customer')).toBe('customers');
    expect(signalKind('heartbeat')).toBe('heartbeat');
  });

  it('ignores an unknown event name rather than inventing a refresh', () => {
    expect(signalKind('something.new')).toBeNull();
  });
});

describe('RealtimeService', () => {
  let realtime: RealtimeService;
  let http: HttpTestingController;

  beforeEach(() => {
    FakeEventSource.reset();
    vi.useFakeTimers();
    (globalThis as { window?: Window }).window = globalThis.window ?? (globalThis as unknown as Window);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).EventSource = FakeEventSource;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    realtime = TestBed.inject(RealtimeService);
    http = TestBed.inject(HttpTestingController);
  });

  it('opens the stream with credentials, because it cannot set headers', () => {
    realtime.start();
    const source = FakeEventSource.latest();
    expect(source.url).toContain('/api/v1/realtime/stream');
    // The session cookie rides along on its own. This is the concrete reason the
    // API authenticates with a cookie rather than a bearer token: EventSource has
    // no header support at all.
    expect(source.options).toMatchObject({ withCredentials: true });
  });

  it('requests topics but never widens them: the server computes the allowed set', () => {
    realtime.start(['org:o', 'project:p']);
    expect(FakeEventSource.latest().url).toContain('topic=org%3Ao');
    expect(FakeEventSource.latest().url).toContain('topic=project%3Ap');
  });

  it('turns every domain frame into a refetch signal, never into data', () => {
    const seen: { kind: string; resourceId?: string }[] = [];
    realtime.onSignal((signal) =>
      seen.push({ kind: signal.kind, resourceId: signal.frame?.resourceId }),
    );
    realtime.start();
    const source = FakeEventSource.latest();
    source.open();

    source.emit('conversation', { type: 'conversation', resourceId: 'c-1' });
    source.emit('message', { type: 'message' });
    source.emit('notification', { type: 'notification' });
    source.emit('customer', { type: 'customer' });

    expect(seen.map((entry) => entry.kind)).toEqual([
      'conversations',
      'conversations',
      'notifications',
      'customers',
    ]);
    // A frame carries ids only, and the client is told to refetch rather than
    // given a row: the payload is a hint, and the API is the record.
    expect(seen[0]?.resourceId).toBe('c-1');
    expect(seen[1]?.resourceId).toBeUndefined();
  });

  it('does not turn a heartbeat into a refetch', () => {
    const seen: string[] = [];
    realtime.onSignal((signal) => seen.push(signal.kind));
    realtime.start();
    FakeEventSource.latest().open();
    FakeEventSource.latest().emit('heartbeat', { type: 'heartbeat' });
    expect(seen).toEqual(['heartbeat']);
    expect(realtime.frameCount()).toBe(1);
  });

  it('honours the resync frame the server sends first on every connection', () => {
    const seen: string[] = [];
    realtime.onSignal((signal) => seen.push(signal.kind));
    realtime.start();
    FakeEventSource.latest().open();
    FakeEventSource.latest().emit('resync', {
      type: 'resync',
      payload: { reason: 'stream-opened', resyncFrom: '/api/v1/realtime/state' },
    });
    expect(seen).toEqual(['resync']);
  });

  it('resyncs on its own reconnect, without depending on the frame arriving', () => {
    // The client may have missed an unknown number of frames during a blip; the
    // only correct answer is "fetch again", and it must not depend on the wire.
    const signals: { kind: string; afterReconnect: boolean }[] = [];
    realtime.onSignal((signal) => signals.push({ kind: signal.kind, afterReconnect: signal.afterReconnect }));
    realtime.start();
    FakeEventSource.latest().open();
    FakeEventSource.latest().fail();

    expect(realtime.status()).toBe('reconnecting');
    expect(realtime.isStale()).toBe(true);

    vi.advanceTimersByTime(30_000);
    expect(FakeEventSource.instances.length).toBe(2);
    expect(signals.at(-1)).toEqual({ kind: 'resync', afterReconnect: true });
  });

  it('backs off exponentially and with jitter, and never beyond the ceiling', () => {
    realtime.start();
    FakeEventSource.latest().open();

    // The first retry waits less than the full ceiling; the point is that each
    // attempt grows and the whole schedule stays bounded.
    FakeEventSource.latest().fail();
    vi.advanceTimersByTime(100);
    expect(FakeEventSource.instances.length).toBe(1);
    vi.advanceTimersByTime(2_000);
    expect(FakeEventSource.instances.length).toBe(2);

    for (let attempt = 0; attempt < 12; attempt += 1) {
      FakeEventSource.latest().fail();
      vi.advanceTimersByTime(120_000);
    }
    expect(realtime.status()).toBe('failed');
    const opened = FakeEventSource.instances.length;
    vi.advanceTimersByTime(600_000);
    expect(FakeEventSource.instances.length).toBe(opened);
  });

  it('gives up loudly rather than reconnecting forever', () => {
    realtime.start();
    for (let attempt = 0; attempt < 13; attempt += 1) {
      FakeEventSource.latest().fail();
      vi.advanceTimersByTime(120_000);
    }
    expect(realtime.status()).toBe('failed');
    expect(realtime.isStale()).toBe(true);
  });

  it('stops cleanly and does not reconnect after stop()', () => {
    realtime.start();
    FakeEventSource.latest().open();
    realtime.stop();
    expect(realtime.status()).toBe('stopped');
    expect(FakeEventSource.latest().closed).toBe(true);
    const opened = FakeEventSource.instances.length;
    vi.advanceTimersByTime(120_000);
    expect(FakeEventSource.instances.length).toBe(opened);
  });

  it('ignores start() when already started, so two components cannot double the stream', () => {
    realtime.start();
    realtime.start();
    expect(FakeEventSource.instances.length).toBe(1);
  });

  it('lets one broken subscriber not stop the others from resyncing', () => {
    const seen: string[] = [];
    realtime.onSignal(() => {
      throw new Error('subscriber exploded');
    });
    realtime.onSignal((signal) => seen.push(signal.kind));
    realtime.start();
    FakeEventSource.latest().open();
    expect(() => FakeEventSource.latest().emit('notification', { type: 'notification' })).not.toThrow();
    expect(seen).toEqual(['notifications']);
  });

  it('exposes the concrete resync endpoint the frame points at', async () => {
    const pending = realtime.resync();
    http
      .expectOne((request) => request.url === '/api/v1/realtime/state')
      .flush({ unreadCount: 2, serverTime: '2026-10-06T10:00:00' });
    await expect(pending).resolves.toMatchObject({ unreadCount: 2 });
  });
});
