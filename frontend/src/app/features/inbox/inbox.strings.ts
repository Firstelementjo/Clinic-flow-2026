import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * The inbox: conversation list, thread, customer panel.
 *
 * Arabic first. The wording that matters most here is the thread's, because a
 * conversation is the one place where being unclear about *who said what* causes
 * real harm — an operator must never confuse a customer's message with their own
 * reply, or an AI draft with a sent message.
 */
export const inboxStrings: Namespace = {
  title: { ar: 'الوارد', en: 'Inbox' },
  description: {
    ar: 'المحادثات المسندة إلىك، وما ينتظر في كل منها.',
    en: 'The conversations assigned to you, and what is waiting in each.',
  },
  refresh: { ar: 'تحديث', en: 'Refresh' },

  // -- list pane ------------------------------------------------------------
  'list.label': { ar: 'قائمة المحادثات', en: 'Conversation list' },
  'list.search': { ar: 'ابحث في المحادثات', en: 'Search conversations' },
  'list.searchHint': {
    ar: 'يبحث في الرقم والموضوع وآخر رسالة.',
    en: 'Searches the ticket number, the subject and the last message.',
  },
  'list.empty': { ar: 'لا توجد محادثات مطابقة', en: 'No matching conversations' },
  /**
   * The five saved views, in the contract's declaration order (ADR 0015).
   *
   * `GET /api/v1/conversations?filter=` is an enum with exactly these five, and an
   * absent value now means `unassigned` — the work queue. The control renders in the
   * order `GET /api/v1/inbox/counts` returned its keys, which is declaration order, and
   * these labels are keyed by that same wire value so the two cannot drift.
   *
   * **They are not five compartments.** The counts response says so in its own
   * description: the views overlap, `assigned-to-me` also holds released threads, and
   * the counts therefore do not sum to the number of open conversations. Nothing on this
   * screen adds them up or presents them as a partition.
   */
  'list.filterUnassigned': {
    ar: 'غير مُسندة',
    en: 'Unassigned',
  },
  'list.filterAllOpen': { ar: 'كل المفتوحة', en: 'All open' },
  'list.filterAssignedToOthers': {
    ar: 'مُسندة إلى غيري',
    en: "Assigned to others",
  },
  'list.filterAssignedToMe': { ar: 'المسندة إليّ', en: 'Assigned to me' },
  'list.filterHistory': { ar: 'السجل', en: 'History' },
  'list.filterGroup': { ar: 'العروض المحفوظة', en: 'Saved views' },

  /**
   * The counts response could not be read.
   *
   * Every one of the five keys is declared `required`, so this is a contract violation
   * and not a state the server can legitimately be in. The alternative — showing a zero
   * for the key that is missing — is the one number on this screen that must never be
   * invented: an operator reading "0 unassigned" concludes the queue is empty.
   */
  'list.countsMalformed': {
    ar: 'تعذّرت قراءة أعداد العروض المحفوظة من الخادم، فلم تُعرض. الحقول غير الصالحة مذكورة أدناه.',
    en: 'The saved-view counts could not be read from the server, so they are not shown. The invalid fields are named below.',
  },

  /**
   * One empty-state sentence per view, because "nothing here" means a different fact in
   * each of the five.
   *
   * A shared sentence would flatten three distinct states into one, and an operator
   * reading "no conversations" on the *all open* tab would be wrong about the state of
   * the queue — which is the one thing this screen exists to tell them.
   */
  'list.emptyUnassigned': {
    ar: 'لا توجد محادثة تنتظر مالكاً. هذا هو الطابور الذي يعمل عليه الجميع.',
    en: 'No conversation is waiting for an owner. This is the queue everyone works from.',
  },
  'list.emptyAllOpen': {
    ar: 'لا توجد أي محادثة مفتوحة في هذا المشروع.',
    en: 'There is no open conversation in this project at all.',
  },
  'list.emptyAssignedToOthers': {
    ar: 'لا توجد محادثة مفتوحة مُسندة إلى شخص آخر.',
    en: 'No open conversation is assigned to somebody else.',
  },
  'list.emptyAssignedToMe': {
    ar: 'لا توجد محادثة على مسؤوليتك الآن.',
    en: 'No conversation is on your plate right now.',
  },
  'list.emptyHistory': {
    ar: 'لا تَجدء مُغلقة أو محلولة تشارك فيها.',
    en: 'Nothing has been closed or resolved on conversations you were involved in.',
  },

  /**
   * The unassigned queue.
   *
   * A new conversation is in **neither** saved view: `assigned-to-me` requires the
   * caller to be the assignee or the last human replier, and neither is true of a
   * brand-new inbound message. The server exposes the queue on its own route,
   * `GET /api/v1/inbox/unassigned`, so this is where it is shown — with the reason
   * stated, because an operator looking for new work in the list and not finding it
   * would otherwise conclude the queue is empty.
   */
  'queue.title': { ar: 'في الانتظار، بلا مالك', en: 'Waiting, unowned' },
  'queue.hint': {
    ar: 'محادثات جديدة لم يرد عليها أحد بعد. لا تظهر في العروض المحفوظة أعلاه لأن ذلك المسار يعرض المحادثات المسندة إليك أو التي ردّدت عليها فقط.',
    en: 'New conversations nobody has answered yet. They are not in the saved views above, because that route shows only conversations assigned to you or that you last replied to.',
  },
  'queue.waiting': { ar: 'بانتظار منذ', en: 'Waiting' },
  'queue.empty': { ar: 'لا شيء في الانتظار', en: 'Nothing is waiting' },
  'queue.emptyBody': {
    ar: 'كل محادثة واردة إسندت إلى مالك.',
    en: 'Every incoming conversation has an owner.',
  },
  'queue.open': { ar: 'فتح', en: 'Open' },

  'list.count': { ar: '{{count}} محادثة', en: '{{count}} conversation(s)' },
  'list.loadMore': { ar: 'تحميل المزيد', en: 'Load more' },
  'list.loadedAll': {
    ar: 'هذه هي كل المحادثات في النطاق الذي تراه.',
    en: 'These are all the conversations in the scope you can see.',
  },

  // -- thread pane ----------------------------------------------------------
  'thread.label': { ar: 'المحادثة', en: 'Conversation' },
  'thread.empty': { ar: 'اختر محادثة', en: 'Choose a conversation' },
  'thread.emptyBody': {
    ar: 'اختر محادثة من القائمة لعرض رسائلها هنا.',
    en: 'Choose a conversation from the list to read it here.',
  },
  'thread.selectPrompt': {
    ar: 'لم تُختر أي محادثة بعد.',
    en: 'No conversation is selected yet.',
  },
  'thread.messageCount': { ar: '{{count}} رسالة', en: '{{count}} message(s)' },
  'thread.newMessages': {
    ar: '{{count}} رسالة جديدة. حدّث قبل الرد.',
    en: '{{count}} new message(s). Refresh before replying.',
  },
  'thread.collision': {
    ar: 'وصلت رسالة أخرى قبل أن ترسل. حدّث المحادثة لتقرأ الرد كاملاً ثم أعد الرد.',
    en: 'Another message arrived before you sent. Refresh to read the full reply, then answer again.',
  },
  'thread.staleWhileReplying': {
    ar: 'البيانات قد تكون قديمة. سيُتحقق من ذلك عند الإرسال.',
    en: 'The data may be out of date. The server checks this when you send.',
  },

  // -- composer -------------------------------------------------------------
  'composer.label': { ar: 'اكتب رداً', en: 'Write a reply' },
  'composer.placeholder': {
    ar: 'اكتب رسالتك للعميل…',
    en: 'Write your reply to the customer…',
  },
  'composer.send': { ar: 'إرسال', en: 'Send' },
  'composer.sending': { ar: 'جارٍ الإرسال…', en: 'Sending…' },
  'composer.empty': { ar: 'اكتب شيئاً قبل الإرسال.', en: 'Write something before sending.' },
  'composer.tooLong': {
    ar: 'الرسالة أطول من الحد المسموح.',
    en: 'The message is longer than the allowed limit.',
  },
  'composer.draftKept': {
    ar: 'احتفظنا بنصّك بعد تعذّر الإرسال، فلم يُفقد.',
    en: 'Your text was kept after the send failed; nothing was lost.',
  },
  'composer.channelWarning': {
    ar: 'الرسالة تُرسَل عبر {{channel}}. تحقّق من القناة قبل الإرسال.',
    en: 'This will be sent over {{channel}}. Check the channel before sending.',
  },

  // -- mode -----------------------------------------------------------------
  'mode.ai': { ar: 'الذكاء الاصطناعي', en: 'AI' },
  'mode.human': { ar: 'موظف', en: 'Human' },
  'mode.label': { ar: 'وضع الرد', en: 'Reply mode' },
  'mode.takeOver': { ar: 'تولّي الرد بنفسك', en: 'Take over replying' },
  'mode.handBack': { ar: 'إعادة الرد للذكاء الاصطناعي', en: 'Hand back to AI' },
  'mode.changed': { ar: 'تغيّر وضع الرد.', en: 'The reply mode changed.' },
  'mode.conflict': {
    ar: 'غيرك غيّر الوضع قبلك. اقرأ الحالة الحالية ثمقرر.',
    en: 'Someone changed the mode before you. Read the current state, then decide.',
  },

  // -- case actions ---------------------------------------------------------
  'case.label': { ar: 'إجراءات الحالة', en: 'Case actions' },
  'case.close': { ar: 'إغلاق المحادثة', en: 'Close conversation' },
  'case.reopen': { ar: 'إعادة فتح', en: 'Reopen' },
  'case.conflict': {
    ar: 'عُدّل شيء بين فتحك هذه الشاشة وطلبك. اقرأ الحالة الحالية ثم أعد المحاولة.',
    en: 'Something changed between you opening this screen and your request. Read the current state, then try again.',
  },
  'case.closed': { ar: 'أُغلقت المحادثة.', en: 'The conversation was closed.' },
  /**
   * A write could not be attempted because the conversation on screen was not a whole
   * conversation.
   *
   * Every action used to return silently in this situation, which is how a dead Reopen
   * button looked identical to a working one. A refusal the operator pressed and cannot
   * see has to be visible; this is the alert the alert region carries.
   */
  'write.notLoaded': {
    ar: 'تعذّر تنفيذ الإجراء لأن المحادثة المعروضة لم تعد كاملة. أُعيد تحميلها؛ أعد المحاولة.',
    en: 'That action could not be carried out because the conversation on screen was not complete. It has been reloaded; please try again.',
  },
  'case.reopened': { ar: 'أُعيد فتح المحادثة.', en: 'The conversation was reopened.' },
  'case.assignToMe': { ar: 'إسنادها إليّ', en: 'Assign to me' },

  /**
   * Closing requires a recorded resolution.
   *
   * The server refuses `POST .../close` until `.../resolution` has been saved, with
   * `Record how the case was resolved before closing it` — a real rule, and a sensible
   * one, but **not documented anywhere in the contract** (no operation declares a 4xx at
   * all), so a client cannot discover it from the document.
   *
   * Rather than fire the write and report the refusal as a generic error, the client
   * checks the same precondition and says what to do first, in the operator's language.
   * The server still enforces it — this is about not wasting a round trip on a request
   * whose outcome is already known, not about replacing the rule.
   */
  'case.closeNeedsResolution': {
    ar: 'سجّل كيف حُلّت الحالة قبل الإغلاق.',
    en: 'Record how the case was resolved before closing it.',
  },

  // -- resolution -----------------------------------------------------------
  'resolution.label': { ar: 'تسجيل الحل', en: 'Record the resolution' },
  'resolution.outcome': { ar: 'النتيجة', en: 'Outcome' },

  /**
   * The three outcomes `CaseOutcome.valueOf` can parse.
   *
   * Not cosmetic: the server runs the submitted value through that enum and throws on
   * anything else, and `SaveResolutionRequest.outcome` is declared `type: string` with no
   * enum — so the contract does not disclose that this field has exactly three legal
   * values. The control is therefore the set of values the wire can carry.
   *
   * `unset` is blank and is the initial state on purpose: a resolution that does not say
   * how the case ended is not a resolution.
   */
  'resolution.outcome.unset': { ar: 'اختر…', en: 'Choose…' },
  'resolution.outcome.resolved': { ar: 'حُلَّت', en: 'Resolved' },
  'resolution.outcome.escalated': { ar: 'صُعّدت', en: 'Escalated' },
  'resolution.outcome.unresolved': { ar: 'لم تُحلّ', en: 'Unresolved' },

  // Both required by `CaseService.validateResolution`, 2..2000 characters. The asterisks
  // in the template are `aria-hidden`; these are the accessible names of the fields, and
  // the requirement is stated by `required` on the control rather than by the label.
  'resolution.customerIssue': { ar: 'مشكلة العميل', en: 'Customer issue' },
  'resolution.agentAction': { ar: 'الإجراء المتخذ', en: 'Action taken' },
  'resolution.usedKnowledge': { ar: 'استُخدمت قاعدة المعرفة', en: 'Knowledge base used' },
  'resolution.documentId': { ar: 'معرّف الوثيقة', en: 'Document id' },
  'resolution.customerAccepted': { ar: 'قبل العميل الحل', en: 'Customer accepted' },
  'resolution.customerSatisfied': { ar: 'العميل راضٍ', en: 'Customer satisfied' },
  'resolution.escalationRequested': { ar: 'طُلب التصعيد', en: 'Escalation requested' },
  'resolution.escalatedToSupervisor': { ar: 'صُعّد للمشرف', en: 'Escalated to a supervisor' },
  'resolution.notes': { ar: 'ملاحظات', en: 'Notes' },
  'resolution.save': { ar: 'حفظ التسجيل', en: 'Save resolution' },
  'resolution.saving': { ar: 'جارٍ الحفظ…', en: 'Saving…' },
  'resolution.saved': { ar: 'حُفظ تسجيل الحل.', en: 'The resolution was saved.' },
  // One message per field rather than one for the form. "An outcome is required before
  // saving" next to an empty *customer issue* box tells the operator nothing, which is
  // the failure this whole set replaced: a 400 whose `details.fields` was discarded in
  // favour of "this page could not be loaded".
  'resolution.outcomeRequired': {
    ar: 'اختر نتيجة للحالة قبل الحفظ.',
    en: 'Choose how the case ended before saving.',
  },
  'resolution.required': {
    ar: 'هذا الحقل مطلوب قبل الحفظ.',
    en: 'This field is required before saving.',
  },
  'resolution.tooShort': {
    ar: 'اكتب حرفين على الأقل.',
    en: 'Write at least two characters.',
  },
  'resolution.tooLong': {
    ar: 'النص أطول من الحدّ المسموح.',
    en: 'The text is longer than the limit.',
  },
  'resolution.none': {
    ar: 'لم يُسجَّل حل لهذه المحادثة بعد.',
    en: 'No resolution has been recorded for this conversation yet.',
  },
  'resolution.recordedBy': { ar: 'سجّله', en: 'Recorded by' },
  'resolution.at': { ar: 'في', en: 'At' },
  'resolution.escalationNote': {
    ar: 'التصعيد حقل ضمن هذا التسجيل. لا توجد قائمة تصعيدات مستقلة في النظام.',
    en: 'Escalation is a field on this record. The system has no separate escalation queue.',
  },

  // -- classification -------------------------------------------------------
  'classification.label': { ar: 'التصنيف', en: 'Classification' },
  'classification.incomplete': {
    ar: 'التصنيف غير مكتمل بعد.',
    en: 'The classification is not complete yet.',
  },
  'classification.suggestion': { ar: 'اقتراح', en: 'Suggestion' },
  'classification.score': { ar: 'درجة الثقة', en: 'Confidence' },
  'classification.source': { ar: 'المصدر', en: 'Source' },
  'classification.answer': { ar: 'الإجابة', en: 'Answer' },
  'classification.unanswered': {
    ar: 'لم تُجب على كل الأسئلة بعد.',
    en: 'Not every question has an answer yet.',
  },
  'classification.save': { ar: 'حفظ الإجابات', en: 'Save answers' },
  'classification.saved': { ar: 'حُفظت الإجابات.', en: 'The answers were saved.' },
  'classification.noQuestions': {
    ar: 'لا توجد أسئلة تصنيف معرّفة لهذا المشروع.',
    en: 'No classification questions are defined for this project.',
  },

  // -- events ---------------------------------------------------------------
  'events.label': { ar: 'سجل النشاط', en: 'Activity' },
  'events.empty': { ar: 'لا يوجد نشاط مسجّل.', en: 'No activity recorded.' },

  // -- customer pane --------------------------------------------------------
  'customer.label': { ar: 'العميل', en: 'Customer' },
  'customer.open': { ar: 'فتح ملف العميل', en: 'Open customer record' },
  'customer.empty': { ar: 'لا يوجد عميل مرتبط.', en: 'No customer is linked.' },
  'customer.none': {
    ar: 'هذه المحادثة غير مرتبطة بعميل مسجّل بعد.',
    en: 'This conversation is not linked to a saved customer yet.',
  },
  'customer.ticket': { ar: 'رقم البلاغ', en: 'Ticket' },
  'customer.channel': { ar: 'القناة', en: 'Channel' },
  'customer.assignee': { ar: 'المسند إليه', en: 'Assigned to' },
  'customer.unassigned': { ar: 'غير مسندة', en: 'Unassigned' },
  'customer.firstSeen': { ar: 'أول ظهور', en: 'First seen' },
  'customer.lastSeen': { ar: 'آخر ظهور', en: 'Last seen' },
  'customer.email': { ar: 'البريد الإلكتروني', en: 'Email' },
  'customer.phone': { ar: 'الهاتف', en: 'Phone' },

  // -- messages -------------------------------------------------------------
  'message.fromCustomer': { ar: 'من العميل', en: 'From the customer' },
  'message.fromAgent': { ar: 'من الموظف', en: 'From the agent' },
  'message.fromAi': { ar: 'من الذكاء الاصطناعي', en: 'From AI' },
  'message.fromSystem': { ar: 'من النظام', en: 'From the system' },
  'message.deliveryPending': { ar: 'قيد الإرسال', en: 'Sending' },
  'message.deliverySent': { ar: 'أُرسلت', en: 'Sent' },
  'message.deliveryDelivered': { ar: 'وصلت', en: 'Delivered' },
  'message.deliveryRead': { ar: 'قُرئت', en: 'Read' },
  'message.deliveryFailed': { ar: 'فشلت', en: 'Failed' },
  'message.failureReason': { ar: 'سبب الفشل', en: 'Failure reason' },

  // -- status vocabulary ----------------------------------------------------
  'status.OPEN': { ar: 'مفتوحة', en: 'Open' },
  'status.PENDING': { ar: 'معلّقة', en: 'Pending' },
  'status.RESOLVED': { ar: 'محلولة', en: 'Resolved' },
  'status.CLOSED': { ar: 'مغلقة', en: 'Closed' },
  'priority.LOW': { ar: 'منخفضة', en: 'Low' },
  'priority.NORMAL': { ar: 'عادية', en: 'Normal' },
  'priority.HIGH': { ar: 'مرتفعة', en: 'High' },
  'priority.URGENT': { ar: 'عاجلة', en: 'Urgent' },
  'direction.INBOUND': { ar: 'واردة', en: 'Inbound' },
  'direction.OUTBOUND': { ar: 'صادرة', en: 'Outbound' },
  'authorType.CUSTOMER': { ar: 'عميل', en: 'Customer' },
  'authorType.AGENT': { ar: 'موظف', en: 'Agent' },
  'authorType.AI': { ar: 'ذكاء اصطناعي', en: 'AI' },
  'authorType.SYSTEM': { ar: 'النظام', en: 'System' },

  // -- queue explanation ----------------------------------------------------
  'queue.reason.NEVER_ASSIGNED': {
    ar: 'لم تُسنَد قط',
    en: 'Never assigned',
  },
  'queue.reason.RELEASED': { ar: 'أُعيدت للقائمة', en: 'Returned to the queue' },
  'queue.reason.REOPENED': { ar: 'أُعيد فتحها', en: 'Reopened' },
} as const;
