import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * Settings copy. Arabic first.
 *
 * The wording for the permission panel is the important one: the screen tells the
 * user what the *server* decided, never what the interface guessed from a role
 * name. A client-side guess is exactly the thing ADR-0001 and
 * `EffectivePermissionService.inspectSelf` were written to make unnecessary.
 */
export const settingsStrings: Namespace = {
  title: { ar: 'الإعدادات', en: 'Settings' },
  description: {
    ar: 'حسابك وما يسمح لك به الخادم، وحالة النظام كما يراها الخادم.',
    en: 'Your account, what the server allows you to do, and the system as the server sees it.',
  },
  refresh: { ar: 'تحديث', en: 'Refresh' },

  'account.title': { ar: 'حسابك', en: 'Your account' },
  'account.name': { ar: 'الاسم', en: 'Name' },
  'account.email': { ar: 'البريد الإلكتروني', en: 'Email' },
  'account.role': { ar: 'الدور', en: 'Role' },
  'account.locale': { ar: 'لغة الحساب', en: 'Account language' },
  'account.organization': { ar: 'المنشأة', en: 'Organization' },
  'account.active': { ar: 'الحساب نشط', en: 'Account is active' },
  'account.inactive': { ar: 'الحساب موقوف', en: 'Account is inactive' },

  'permissions.title': { ar: 'صلاحياتك الفعلية', en: 'Your effective permissions' },
  'permissions.summary': {
    ar: 'تملك {{granted}} من {{total}} صلاحية. هذه إجابة الخادم، لا تخمين الواجهة.',
    en: 'You hold {{granted}} of {{total}} permissions. This is the server’s answer, not an interface guess.',
  },
  'permissions.granted': { ar: 'تملك', en: 'Granted' },
  'permissions.missing': { ar: 'لا تملك', en: 'Not held' },
  'permissions.projects': { ar: 'مشاريعك', en: 'Your projects' },
  'permissions.projectsEmpty': {
    ar: 'لم تُسنَد إليك أي مشروع. تواصل مع المسؤول لإضافة واحد.',
    en: 'No project is assigned to you. Ask an admin to add one.',
  },
  'permissions.pending': {
    ar: 'لم تُقرأ الصلاحيات بعد. سيُعاد تحميلها تلقائياً.',
    en: 'Permissions have not been read yet. They reload automatically.',
  },

  'liveness.title': { ar: 'الخدمة', en: 'Service' },
  'liveness.status': { ar: 'الحالة', en: 'Status' },
  'liveness.version': { ar: 'الإصدار', en: 'Version' },
  'liveness.route': { ar: 'المسار', en: 'Route' },

  'queue.title': { ar: 'الطوابير والمهام', en: 'Queues and jobs' },
  'queue.dueNow': { ar: 'مستحقة الآن', en: 'Due now' },
  'queue.oldestPending': { ar: 'أقدم معلّق', en: 'Oldest pending' },
  'queue.attempts': { ar: 'محاولات في النافذة', en: 'Attempts in window' },
  'queue.failures': { ar: 'إخفاقات في النافذة', en: 'Failures in window' },
  'queue.deadLetters': { ar: 'رسائل ميتة', en: 'Dead letters' },
  'queue.failureRate': { ar: 'معدل الإخفاق', en: 'Failure rate' },
  'queue.failureRateUnknown': {
    ar: 'لا توجد محاولات في النافذة، وهذا ليس معدلاً صفرياً.',
    en: 'No attempts in the window, which is not a 0% rate.',
  },
  'queue.stateCounts': { ar: 'حالات صندوق الصادر', en: 'Outbox states' },

  'stream.title': { ar: 'الاتصال المباشر', en: 'Live connection' },
  'stream.unread': { ar: 'غير مقروء', en: 'Unread' },
  'stream.subscribers': { ar: 'مشتركون', en: 'Subscribers' },
  'stream.connections': { ar: 'اتصالات مفتوحة', en: 'Connections opened' },
  'stream.resyncs': { ar: 'عمليات مزامنة', en: 'Resyncs sent' },
  'stream.multiInstance': { ar: 'جسر متعدد النسخ', en: 'Multi-instance bridge' },
  'stream.enabled': { ar: 'مفعّل', en: 'Enabled' },
  'stream.disabled': { ar: 'غير مفعّل', en: 'Disabled' },

  'connectors.title': { ar: 'الموصّلات', en: 'Connectors' },
  'connectors.empty': {
    ar: 'لا توجد موصّلات في هذه النافذة.',
    en: 'No connectors in this window.',
  },
  'connectors.channel': { ar: 'القناة', en: 'Channel' },
  'connectors.state': { ar: 'الحالة', en: 'State' },
  'connectors.attempts': { ar: 'محاولات', en: 'Attempts' },
  'connectors.failures': { ar: 'إخفاقات', en: 'Failures' },

  'knowledge.title': { ar: 'فهرسة المعرفة', en: 'Knowledge indexing' },
  'knowledge.freshness': { ar: 'حداثة الفهرس', en: 'Index freshness' },
  'knowledge.gaps': { ar: 'فجوات المعرفة', en: 'Knowledge gaps' },
  'knowledge.gapsEmpty': {
    ar: 'لا فجوات مسجّلة: كل سؤال وجد إجابة.',
    en: 'No gaps recorded: every question found an answer.',
  },
  'knowledge.gapQuestion': { ar: 'السؤال', en: 'Question' },
  'knowledge.gapHits': { ar: 'إجابات', en: 'Answers' },

  'audit.title': { ar: 'سجل التدقيق', en: 'Audit trail' },
  'audit.scope': { ar: 'النطاق', en: 'Scope' },
  'audit.since': { ar: 'منذ', en: 'Since' },
  'audit.events': { ar: 'الأحداث', en: 'Events' },
  'audit.empty': { ar: 'لا سجلات في آخر ١٦٨ ساعة.', en: 'No entries in the last 168 hours.' },
  'audit.action': { ar: 'الإجراء', en: 'Action' },
  'audit.actor': { ar: 'الفاعل', en: 'Actor' },
  'audit.resource': { ar: 'المورد', en: 'Resource' },
  'audit.when': { ar: 'متى', en: 'When' },

  'security.title': { ar: 'أحداث الأمان', en: 'Security events' },
  'security.empty': {
    ar: 'لا تجديدات وصول مسجّلة في آخر ١٦٨ ساعة.',
    en: 'No access denials recorded in the last 168 hours.',
  },
  'security.eventType': { ar: 'نوع الحدث', en: 'Event type' },
  'security.reason': { ar: 'السبب', en: 'Reason' },
  'security.principal': { ar: 'الفاعل', en: 'Principal' },
  'security.address': { ar: 'العنوان', en: 'Address' },
  'security.correlation': { ar: 'رقم الارتباط', en: 'Correlation id' },

  'unit.day': { ar: 'ي', en: 'd' },
  'unit.hour': { ar: 'س', en: 'h' },
  'unit.minute': { ar: 'د', en: 'm' },
  'unit.second': { ar: 'ث', en: 's' },
} as const;
