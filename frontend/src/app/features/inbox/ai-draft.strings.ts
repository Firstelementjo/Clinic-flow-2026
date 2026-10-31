import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * The pending AI draft, as it is shown beside a conversation.
 *
 * Arabic first, and the wording is the load-bearing part of this file rather than
 * decoration. A draft is text the AI produced that **no customer has received**,
 * and the whole ruling of the capability is that a person decides whether it ever
 * will be. So every string here is written to remove ambiguity rather than to be
 * brief:
 *
 * - `badge` is a single word that cannot be mistaken for a delivery state. The
 *   sent/delivered vocabulary already exists in `inbox.message.*` and is
 *   deliberately **not** reused here, because reusing it would put a draft into
 *   the same vocabulary the operator reads to decide what the customer already
 *   has.
 * - `notSent` is the sentence that does the real work. A badge alone is a label;
 *   this is a statement about the world, and it is the one an operator skims.
 * - `withheld` says what happened and who did it. It is shown when the
 *   conversation is visibly in human mode and the route declined to offer a
 *   draft, which is a fact the operator can already see on the thread — not an
 *   inference about a row they cannot see.
 *
 * There is deliberately no string for a "forbidden conversation". The route
 * answers a cross-tenant request with the same 404 as an empty queue, so a
 * surface that rendered the two differently would be an existence oracle.
 */
export const aiDraftStrings: Namespace = {
  'label': { ar: 'مسودة الذكاء الاصطناعي', en: 'AI draft' },

  /**
   * The badge on the card.
   *
   * One word, in both scripts, and never styled as a delivery chip. `مسودة`
   * means a draft in the sense of "not yet sent"; it is not the word for a
   * message in flight.
   */
  'badge': { ar: 'مسودة', en: 'Draft' },

  /**
   * The sentence under the body. This is the claim the whole surface exists to
   * make, so it is stated positively rather than implied by a badge.
   */
  'notSent': {
    ar: 'لم تُرسل هذه المسودة إلى العميل بعد. لن تصل إليه إلا إذا أرسلتها بنفسك.',
    en: 'This draft has not been sent to the customer. It reaches them only if you send it yourself.',
  },

  'sent': { ar: 'أُرسلت المسودة.', en: 'The draft was sent.' },
  'sending': { ar: 'جارٍ الإرسال…', en: 'Sending…' },

  /** The control. States the action, per the design system, and never "Submit". */
  'send': { ar: 'إرسال المسودة', en: 'Send the draft' },

  /**
   * What the send button may not do. A single sentence under the button, always
   * present while the draft is on screen, so the boundary is stated before the
   * press rather than discovered after it.
   */
  'sendHint': {
    ar: 'الإرسال يستخدم مسار الرد نفسه، ويسجّل رسالتك باسمك.',
    en: 'Sending uses the same reply route, and records the message under your name.',
  },

  /** Provenance: which run produced this text. Monospace in the template. */
  'run': { ar: 'تشغيل', en: 'Run' },
  'created': { ar: 'أُنشئت', en: 'Created' },

  // -- no draft -------------------------------------------------------------

  /**
   * There is no pending draft.
   *
   * Deliberately unexciting, and deliberately **not** an error: the route answers
   * this with the same 404 it answers a foreign conversation with, so treating
   * it as a failure would misinform an operator and disclose that a lookup
   * found something. It says what the surface is for, so an operator who has not
   * seen a draft yet knows the panel is working rather than broken.
   */
  'none': { ar: 'لا توجد مسودة بانتظار المراجعة في هذه المحادثة.', en: 'No draft is waiting for review on this conversation.' },

  // -- withheld by a human takeover -----------------------------------------

  /**
   * A human took the conversation over, so the draft is withheld.
   *
   * The backend answers this with the **same 404** as "no draft" — deliberately,
   * so the route cannot be used to ask whether the AI produced anything. What
   * makes it sayable here is a fact the operator can already see: the
   * conversation is in human mode on the thread they are reading. So the copy
   * states the mode, which is visible, and not the existence of a draft, which
   * is not.
   *
   * It is worded as what the system did, not as what was withheld: an operator
   * reading "an AI draft was withheld" learns that an AI run existed, which is
   * exactly the thing the route declines to disclose.
   */
  'withheld': {
    ar: 'تولّى موظف هذه المحادثة، لذلك لا تُعرض هنا مسودة الذكاء الاصطناعي.',
    en: 'A human has taken over this conversation, so no AI draft is offered here.',
  },
  'withheldTitle': {
    ar: 'تولّى الموظف الرد',
    en: 'Taken over by a human',
  },

  // -- permission -----------------------------------------------------------

  /**
   * The caller does not hold `ai-run:read`.
   *
   * Shown when the request was never issued *and* when the server refuses one
   * that was, so the two are indistinguishable from outside — which they must be.
   * It says the account may not read drafts; it never says the feature is
   * missing, because it exists.
   */
  'forbidden': {
    ar: 'حسابك لا يملك صلاحية قراءة مسودات الذكاء الاصطناعي. الخادم هو الذي يقرر، وهذا هو السلوك الصحيح.',
    en: 'Your account does not have permission to read AI drafts. The server decides this, and that is correct.',
  },

  // -- failure --------------------------------------------------------------

  /** A 5xx or a dead connection. The one state here that really is an error. */
  'error': {
    ar: 'تعذّرت قراءة المسودة من الخادم.',
    en: 'The draft could not be read from the server.',
  },
  'retry': { ar: 'إعادة المحاولة', en: 'Try again' },
} as const;