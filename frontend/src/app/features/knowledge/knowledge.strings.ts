import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * Knowledge, AI policy, channels and escalation — one namespace for group 8.
 *
 * These four subjects share a screen because they share their honest state: for all
 * four, the committed contract carries a **policy or health read model** and no
 * **content read model**. What exists can be shown truthfully; what does not is
 * stated rather than invented.
 */
export const knowledgeStrings: Namespace = {
  title: { ar: 'المعرفة والوكيل', en: 'Knowledge & agent' },
  description: {
    ar: 'نضج البحث والفجوات، وسياسة أدوات الذكاء الاصطناعي، وقدرة القنوات.',
    en: 'Search freshness and gaps, AI tool policy, and what the channels can do.',
  },
  refresh: { ar: 'تحديث', en: 'Refresh' },

  // -- search freshness -----------------------------------------------------
  'freshness.title': { ar: 'نضج البحث', en: 'Search freshness' },
  'freshness.hint': {
    ar: 'متى شُيّكت آخر مرة، وكم قطعة منها قابلة للاسترجاع.',
    en: 'When each was last indexed, and how many of its chunks are retrievable.',
  },
  'freshness.empty': { ar: 'لا توجد قواعد معرفة مفهرسة', en: 'No indexed knowledge base' },
  'freshness.emptyBody': {
    ar: 'لم تُفهرَس أي قاعدة معرفة في هذا النطاق بعد.',
    en: 'No knowledge base has been indexed in this scope yet.',
  },
  'freshness.source': { ar: 'المصدر', en: 'Source' },
  'freshness.chunks': { ar: 'القطع', en: 'Chunks' },
  'freshness.embedded': { ar: 'آخر تضمين', en: 'Last embedded' },
  'freshness.never': { ar: 'لم يُضمَّن', en: 'Never embedded' },

  // -- gaps -----------------------------------------------------------------
  'gaps.title': { ar: 'فجوات المعرفة', en: 'Knowledge gaps' },
  'gaps.hint': {
    ar: 'أسئلة لم تجد إجابة في المعرفة، مرتّبة بما تكرّر أكثر.',
    en: 'Questions that found no answer in the knowledge base, most frequent first.',
  },
  'gaps.empty': { ar: 'لا فجوات', en: 'No gaps' },
  'gaps.emptyBody': {
    ar: 'لم تسجّل أي إجابة مفقودة.',
    en: 'No unanswered question has been recorded.',
  },
  'gaps.question': { ar: 'السؤال', en: 'Question' },
  'gaps.occurrences': { ar: 'مرات التكرار', en: 'Occurrences' },
  'gaps.hits': { ar: 'تكرار', en: 'hits' },

  // -- AI policy ------------------------------------------------------------
  'ai.title': { ar: 'حوكمة الذكاء الاصطناعي', en: 'AI governance' },
  'ai.hint': {
    ar: 'الأدوات المسموح بها، والموازنات، والطلبات التي تنتظر موافقة، والمكالمات المحظورة.',
    en: 'Which tools are allowed, the budgets, the approvals waiting on a decision, and the blocked calls.',
  },
  'ai.tools': { ar: 'الأدوات', en: 'Tools' },
  'ai.policies': { ar: 'السياسات', en: 'Policies' },
  'ai.budgets': { ar: 'الموازنات', en: 'Budgets' },
  'ai.approvals': { ar: 'الموافقات', en: 'Approvals' },
  'ai.blocked': { ar: 'المكالمات المحظورة', en: 'Blocked calls' },
  'ai.redactions': { ar: 'عمليات الحجب', en: 'Redactions' },
  'ai.empty': { ar: 'لا سجلات', en: 'No records' },
  'ai.emptyBody': {
    ar: 'لم يسجّل الخادم أياً في هذا القسم بعد.',
    en: 'The server has recorded nothing in this section yet.',
  },
  'ai.readOnly': {
    ar: 'هذا القسم للقراءة فقط. كل قرار يُتخذ عبر أمر صريح، لا من هنا.',
    en: 'This section is read-only. Every decision is taken as an explicit command, not from here.',
  },

  // -- channels -------------------------------------------------------------
  'channels.title': { ar: 'القنوات', en: 'Channels' },
  'channels.hint': {
    ar: 'ما يستطيع كل موصّل فعله، وما إذا كان متصلاً.',
    en: 'What each connector can do, and whether it is connected.',
  },
  'channels.capabilities': { ar: 'القدرات', en: 'Capabilities' },
  'channels.connections': { ar: 'الاتصالات', en: 'Connections' },
  'channels.connectionsUnavailable': {
    ar: 'لا يمكن عرض حالة الاتصالات: المسار في العقد يطلب مُدخل TenantContext لا يمكن إرساله على سلسلة الاستعلام، ويحلّه الخادم من الجلسة أصلاً.',
    en: 'Connection health cannot be shown: the route in the contract requires a TenantContext query parameter that cannot be serialised onto a query string, and the server resolves it from the session anyway.',
  },
  'channels.empty': { ar: 'لا موصّلات', en: 'No connectors' },
  'channels.emptyBody': {
    ar: 'لم يسجّل الخادم أي موصّل في هذا النطاق.',
    en: 'The server has recorded no connector in this scope.',
  },
  'channels.channel': { ar: 'الموصّل', en: 'Connector' },
  'channels.capability': { ar: 'القدرة', en: 'Capability' },
  'channels.supported': { ar: 'مدعوم', en: 'Supported' },

  // -- escalation -----------------------------------------------------------
  'escalation.title': { ar: 'التصعيد', en: 'Escalation' },
  'escalation.body': {
    ar: 'التصعيد حقلان ضمن تسجيل الحل، ولا توجد قائمة تصعيدات مستقلة في النظام. يُسجَّل التصعيد من داخل المحادثة نفسها.',
    en: 'Escalation is two fields on a case resolution. There is no separate escalation queue; it is recorded from inside the conversation.',
  },
  'escalation.where': { ar: 'أين يُسجَّل', en: 'Where it is recorded' },
  'escalation.toSupervisor': { ar: 'التصعيد للمشرف', en: 'Escalation to a supervisor' },
  'escalation.requested': { ar: 'طلب التصعيد', en: 'Escalation requested' },
  'escalation.inConversation': { ar: 'تسجيل الحل داخل المحادثة', en: 'The case resolution inside the conversation' },

  // -- not available --------------------------------------------------------
  'unavailable.documents': { ar: 'المستندات وقواعد المعرفة', en: 'Documents and knowledge bases' },
  'unavailable.documentsBody': {
    ar: 'لا يوجد مسار في العقد الموقّع لإدارة المستندات أو قواعد المعرفة. ما تراه أعلاه هو نضج الفهرسة والفجوات فقط.',
    en: 'The published contract has no route for managing documents or knowledge bases. What you see above is index freshness and gaps only.',
  },
  'unavailable.agents': { ar: 'وكلاء الذكاء الاصطناعي', en: 'AI agents' },
  'unavailable.agentsBody': {
    ar: 'لا يوجد مسار لوكلاء الذكاء الاصطناعي أو التشغيلات أو المطالبات. المتاح هو سياسة الأدوات والموازنات والموافقات.',
    en: 'There is no route for AI agents, runs or prompts. What exists is tool policy, budgets and approvals.',
  },
} as const;
