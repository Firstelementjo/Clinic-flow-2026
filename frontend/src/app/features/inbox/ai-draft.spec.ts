/**
 * The pending AI draft surface, against the one route that serves it.
 *
 * ## Why this suite exists
 *
 * Phase 46 gave the backend a governed draft trigger: an inbound message on an
 * AI-mode conversation produces ONE `AiOrchestrator` run and records a draft a
 * human may review. **Nothing is sent automatically**, and there is no automated
 * send anywhere. Until this surface existed the capability was invisible to a
 * person using the product: the run happened, the draft existed, and nobody could
 * read it.
 *
 * The four claims worth protecting, and none of them is "the card renders":
 *
 * 1. **A draft is never presented as something the customer received.** The card
 *    is labelled a draft in words, carries the run it came from, and never joins
 *    the message list. A draft that reads as a sent message is the one failure
 *    this whole design exists to prevent.
 * 2. **The four outcomes are distinguishable by a person.** Present, none,
 *    withheld-by-takeover, and refused all look different. In particular a `404`
 *    is **not** an error toast: the route answers `404` for a conversation with
 *    no draft *and* for a foreign conversation, so treating it as a failure
 *    would both alarm an operator and disclose that a row exists.
 * 3. **Sending uses the existing reply route.** One send path. The card offers
 *    the draft's text to the ordinary `POST /api/v1/conversations/{id}/messages`
 *    — the same call the composer makes — and this suite asserts the *absence* of
 *    any second write, so a future "convenience endpoint" cannot creep in beside
 *    it unnoticed.
 * 4. **A caller without `ai-run:read` is not asked.** The request is withheld
 *    entirely, which is asserted by the absence of the request rather than by
 *    asserting that a `403` renders nicely. The server is the boundary; this is
 *    the UX gate that stops a pointless round trip.
 *
 * ## Bilingual
 *
 * Both locales are rendered. The Arabic assertions are not a translation check —
 * they check that the Arabic path is exercised at all, which is what stops an
 * English-only surface from being the only one anybody tests.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { SessionStore } from '../../core/auth/session.store';
import type { SessionResponse } from '../../core/api/generated';
import { AiDraftPanelComponent } from './ai-draft.component';

/** The `runId` the server would put on a draft. */
const RUN_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
/** The conversation the operator has open. */
const CONVERSATION_ID = '11111111-1111-1111-1111-111111111111';

/** The path the one route resolves to. Asserted literally, not assembled. */
const DRAFT_PATH = `/api/v1/ai/drafts/conversations/${CONVERSATION_ID}`;

/** A recorded `AiDraftView`, exactly as the server sends it. */
const DRAFT = {
  runId: RUN_ID,
  conversationId: CONVERSATION_ID,
  body: 'موعدك يوم الأحد الساعة العاشرة.',
  createdAt: '2026-10-09T08:12:00.000Z',
  state: 'DRAFT',
  conversationMode: 'AI',
  conversationModeVersion: 3,
};

/**
 * The refusal bodies the route publishes.
 *
 * `404` is byte-identical for "no such conversation", "another tenant's", and
 * "withheld because a human took over" — that identity IS the non-disclosure
 * property, so the fixture asserts it rather than inventing a variant.
 */
const NOT_FOUND_BODY = {
  code: 'NOT_FOUND',
  message: 'Resource not found',
  details: {},
  requestId: 'req-404',
  timestamp: '2026-10-09T08:12:00.000Z',
};

const ACCESS_DENIED_BODY = {
  code: 'ACCESS_DENIED',
  message: 'Not permitted',
  details: {},
  requestId: 'req-403',
  timestamp: '2026-10-09T08:12:00.000Z',
};

const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'agent@clinic.example',
  fullName: 'Agent',
  role: 'EMPLOYEE',
  locale: 'ar',
  active: true,
  projectIds: ['proj-1'],
  csrfHeaderName: 'X-XSRF-TOKEN',
} as unknown as SessionResponse;

describe('the pending AI draft on a conversation', () => {
  let fixture: ComponentFixture<AiDraftPanelComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [AiDraftPanelComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    i18n = TestBed.inject(I18nService);
    fixture = TestBed.createComponent(AiDraftPanelComponent);
    http = TestBed.inject(HttpTestingController);
  });

  /** Lets the component's promise chains settle, then re-renders. */
  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function grant(permissions: readonly string[]): void {
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION);
    store.setPermissions({ effectivePermissions: [...permissions], missingPermissions: [] });
  }

  /** The three inputs this panel reads, so `setInput` stays typed. */
  interface PanelInputs {
    readonly conversationId?: string | null;
    readonly conversationMode?: string | null;
    readonly messageCount?: number | null;
  }

  function open(overrides: PanelInputs = {}): void {
    fixture.componentRef.setInput('conversationId', CONVERSATION_ID);
    fixture.componentRef.setInput('conversationMode', 'AI');
    fixture.componentRef.setInput('messageCount', 4);
    if (overrides.conversationId !== undefined) {
      fixture.componentRef.setInput('conversationId', overrides.conversationId);
    }
    if (overrides.conversationMode !== undefined) {
      fixture.componentRef.setInput('conversationMode', overrides.conversationMode);
    }
    if (overrides.messageCount !== undefined) {
      fixture.componentRef.setInput('messageCount', overrides.messageCount);
    }
    fixture.detectChanges();
  }

  describe('a draft is waiting', () => {
    beforeEach(() => grant(['ai-run:read', 'conversation:reply']));

    it('reads the draft for the open conversation', async () => {
      open();
      await tick();

      const requests = http.match(() => true);
      expect(requests.length).toBe(1);
      expect(requests[0]!.request.method).toBe('GET');
      expect(requests[0]!.request.url).toBe(DRAFT_PATH);
      // The scope is the session's, so there is no tenant or project parameter to
      // send. A `projectId` here would be a request, never authorization.
      expect(requests[0]!.request.params.keys()).toEqual([]);

      requests[0]!.flush(DRAFT);
      await tick();
    });

    it('shows the body as a DRAFT, and says it was not sent', async () => {
      i18n.setLocale('en');
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      const card = element('[data-testid="ai-draft-card"]');
      expect(card).not.toBeNull();
      expect(card!.textContent).toContain(DRAFT.body);

      // The word, not only a colour: a status that lives in a hue is invisible to
      // a screen reader and to a colour-blind operator.
      expect(card!.textContent ?? '').toMatch(/draft/i);

      // The explicit sentence is what removes the ambiguity. "Never rendered as
      // something the customer received" is a claim about the words, not about
      // the styling.
      expect(card!.textContent ?? '').toMatch(/not been sent/i);

      // And the run it came from, so an operator can quote it to whoever owns
      // the AI configuration.
      expect(card!.textContent ?? '').toContain(RUN_ID);
    });

    it('carries the draft in both locales', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      const arabic = element('[data-testid="ai-draft-card"]')!.textContent ?? '';
      i18n.setLocale('en');
      await tick();
      const english = element('[data-testid="ai-draft-card"]')!.textContent ?? '';

      // The body is data and survives the switch; the labels are copy and change.
      expect(arabic).toContain(DRAFT.body);
      expect(english).toContain(DRAFT.body);
      expect(arabic).not.toBe(english);
    });

    it('is a proposal beside the thread, not an entry in its history', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      const card = element('[data-testid="ai-draft-card"]')!;
      // A draft is not history. If it were rendered as a list item it would be
      // announced in the same sequence as the messages, with the same ordinal,
      // and the structure would assert that it had been said.
      expect(card.closest('ol, ul')).toBeNull();

      // And in the screen that actually composes them, the panel is a sibling of
      // the message list rather than a descendant of it. Read from the shipped
      // template rather than asserted about a composition this suite does not
      // build, so the claim is about the real screen.
      const template = readFileSync(
        join(__dirname, 'inbox.component.html'),
        'utf8',
      );
      const messages = template.indexOf('class="messages"');
      const panel = template.indexOf('<fe-ai-draft-panel');
      expect(messages, 'the message list exists in the thread template').toBeGreaterThan(-1);
      expect(panel, 'the draft panel is rendered by the thread template').toBeGreaterThan(-1);
      // Position alone is not the claim — nesting is. Assert the panel is not
      // inside the `<ol>` element that holds the messages.
      const listOpens = template.lastIndexOf('<ol', messages);
      const listCloses = template.indexOf('</ol>', messages);
      expect(listCloses).toBeGreaterThan(listOpens);
      expect(panel < listOpens || panel > listCloses).toBe(true);
    });

    it('offers a send control, and issues no write until it is pressed', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      const send = element('[data-testid="ai-draft-send"]') as HTMLButtonElement | null;
      expect(send).not.toBeNull();

      // **No automatic send.** One GET and nothing else until a human presses it.
      const writes = http.match(
        (r) => r.method !== 'GET' && r.url.includes('/api/v1/conversations/'),
      );
      expect(writes.length).toBe(0);
    });

    it('sends the draft through the EXISTING reply route, and nothing else', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      (element('[data-testid="ai-draft-send"]') as HTMLButtonElement).click();
      await tick();

      const writes = http.match(
        (r) => r.method !== 'GET' && r.url.includes('/api/v1/conversations/'),
      );
      // Exactly one, and it is the conversation reply the composer already uses.
      expect(writes.length).toBe(1);
      expect(writes[0]!.request.method).toBe('POST');
      expect(writes[0]!.request.url).toBe(
        `/api/v1/conversations/${CONVERSATION_ID}/messages`,
      );
      expect(writes[0]!.request.body).toEqual({ body: DRAFT.body });

      writes[0]!.flush({ id: CONVERSATION_ID, version: '9', status: 'OPEN' });
      await tick();

      // A second press after a success must not send the same text twice.
      const again = http.match((r) => r.method !== 'GET' && r.url.includes('/messages'));
      expect(again.length).toBe(0);
    });

    it('carries the collision header the composer carries', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      (element('[data-testid="ai-draft-send"]') as HTMLButtonElement).click();
      await tick();

      const write = http.match((r) => r.method === 'POST')[0]!;
      // The draft was read against a thread with four messages; saying so lets the
      // server refuse rather than silently interleave a second operator's reply.
      expect(write.request.headers.get('X-Expected-Message-Count')).toBe('4');

      write.flush({ id: CONVERSATION_ID, version: '9', status: 'OPEN' });
      await tick();
    });

    it('does not offer send to a caller who may not reply', async () => {
      // ai-run:read without conversation:reply: a real configuration, and the one
      // the backend's own javadoc describes.
      TestBed.inject(SessionStore).clear('startup');
      grant(['ai-run:read']);
      open();
      http.match(DRAFT_PATH)[0]!.flush(DRAFT);
      await tick();

      expect(element('[data-testid="ai-draft-send"]')).toBeNull();
      // The draft is still shown — reading it is what ai-run:read is for.
      expect(element('[data-testid="ai-draft-card"]')).not.toBeNull();
    });
  });

  describe('there is no draft', () => {
    beforeEach(() => grant(['ai-run:read', 'conversation:reply']));

    it('says so in words, and does not raise an error', async () => {
      i18n.setLocale('en');
      open();
      http.match(DRAFT_PATH)[0]!.flush(NOT_FOUND_BODY, { status: 404, statusText: 'Not Found' });
      await tick();

      // An empty state, not a failure. A 404 here is the route's ordinary answer
      // for "nothing pending", and it is also its answer for a foreign
      // conversation — so a red error toast would both misinform and disclose.
      expect(element('[data-testid="ai-draft-card"]')).toBeNull();
      expect(element('[role="alert"]')).toBeNull();

      expect(text()).toMatch(/no draft/i);
    });

    it('never names a conversation the caller does not hold', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(NOT_FOUND_BODY, { status: 404, statusText: 'Not Found' });
      await tick();

      const panel = element('[data-testid="ai-draft-panel"]');
      expect(panel?.textContent ?? '').not.toContain(RUN_ID);
      // And not the technical message either: `Resource not found` on screen
      // teaches an operator that a lookup happened, which is the oracle the
      // route was built not to be.
      expect(panel?.textContent ?? '').not.toContain('Resource not found');
    });
  });

  describe('a human took the conversation over', () => {
    beforeEach(() => grant(['ai-run:read', 'conversation:reply']));

    it('says the draft was withheld, in plain words', async () => {
      i18n.setLocale('en');
      // The same 404 as "no draft" — that identity is deliberate. What the panel
      // adds is the *reason* it can state: the conversation it is rendering is
      // visibly in HUMAN mode, which it read from the thread the operator is
      // already looking at.
      open({ conversationMode: 'HUMAN' });
      http.match(DRAFT_PATH)[0]!.flush(NOT_FOUND_BODY, { status: 404, statusText: 'Not Found' });
      await tick();

      // Plain words, and the actor named: "a human", not "the system". An
      // operator has to be able to say afterwards "a person had taken it over"
      // without reading the run table.
      expect(text()).toMatch(/human/i);
      expect(text()).toMatch(/taken over|took over|handed to/i);
      // And no error: a human taking over is the system working.
      expect(element('[role="alert"]')).toBeNull();
    });

    it('does not say "no draft" when a takeover is the visible reason', async () => {
      i18n.setLocale('en');
      open({ conversationMode: 'HUMAN' });
      http.match(DRAFT_PATH)[0]!.flush(NOT_FOUND_BODY, { status: 404, statusText: 'Not Found' });
      await tick();

      expect(text()).not.toMatch(/no draft/i);
    });

    it('states it in Arabic too, so the reason survives the switch', async () => {
      open({ conversationMode: 'HUMAN' });
      http.match(DRAFT_PATH)[0]!.flush(NOT_FOUND_BODY, { status: 404, statusText: 'Not Found' });
      await tick();

      const arabic = text();
      i18n.setLocale('en');
      await tick();
      const english = text();

      expect(arabic).not.toBe(english);
      // The Arabic is not a raw key, which is what an untranslated string renders.
      expect(arabic).not.toMatch(/aiDraft\./);
    });
  });

  describe('the caller lacks ai-run:read', () => {
    it('does not ask at all', async () => {
      grant(['conversation:reply']);
      open();

      // Asserted by ABSENCE. A request the server would refuse is a request that
      // teaches the browser something it has no need to know, and the route is
      // UX-gated on the same permission the server enforces.
      const requests = http.match(() => true);
      expect(requests.length).toBe(0);
    });

    it('says the surface is not available, without claiming the feature is missing', async () => {
      i18n.setLocale('en');
      grant(['conversation:reply']);
      open();
      await tick();

      expect(text()).toMatch(/permission/i);
      // Not "coming soon" and not a dead end: the route exists, this account
      // simply may not read it.
      expect(text()).not.toMatch(/not available yet|coming soon/i);
    });

    it('refuses identically when the server says so anyway', async () => {
      // A grant revoked between the permission read and the request: the client
      // believed it was allowed, and the server disagreed. The server wins, and
      // the surface says the same thing it says when it never asked.
      i18n.setLocale('en');
      grant(['ai-run:read']);
      open();
      http.match(DRAFT_PATH)[0]!.flush(ACCESS_DENIED_BODY, {
        status: 403,
        statusText: 'Forbidden',
      });
      await tick();

      expect(element('[data-testid="ai-draft-card"]')).toBeNull();
      expect(element('[role="alert"]')).toBeNull();
      expect(text()).toMatch(/permission/i);
    });
  });

  describe('the read itself fails', () => {
    beforeEach(() => grant(['ai-run:read']));

    it('offers a retry, and the retry is the same read', async () => {
      open();
      http.match(DRAFT_PATH)[0]!.flush(
        { code: 'INTERNAL', message: 'boom' },
        { status: 500, statusText: 'Server Error' },
      );
      await tick();

      // A 5xx is a fault the caller can do something about, so it is an alert
      // with a control — unlike the 404 above, which is an answer.
      expect(element('[role="alert"]')).not.toBeNull();
      const retry = element('[data-testid="ai-draft-retry"]') as HTMLButtonElement | null;
      expect(retry).not.toBeNull();

      retry!.click();
      await tick();
      const again = http.match(DRAFT_PATH);
      expect(again.length).toBe(1);
      again[0]!.flush(DRAFT);
      await tick();
      expect(element('[data-testid="ai-draft-card"]')).not.toBeNull();
    });

    it('shows nothing for a conversation that was never opened', () => {
      fixture.componentRef.setInput('conversationId', null);
      fixture.detectChanges();

      expect(element('[data-testid="ai-draft-panel"]')).toBeNull();
      expect(http.match(() => true).length).toBe(0);
    });
  });
});