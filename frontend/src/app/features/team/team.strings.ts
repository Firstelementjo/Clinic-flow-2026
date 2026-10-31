import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * Team.
 *
 * ## What this screen is, honestly
 *
 * `docs/migration-AGENT.md` §4.6 describes a team table plus an import card with a
 * dashed drop area. Only the table has a route. `GET /api/v1/admin/identity` is an
 * **administration** read model — users, roles, memberships, live sessions — and it
 * is what this screen reads.
 *
 * So the screen says which half it is showing. Availability, working hours, skills,
 * capacity and CSV/XLSX import have **no route in the committed contract**; rather
 * than draw a drop area that would 404 on submit, they are presented as an explicit
 * not-available region naming the absent routes. An operator can then file
 * "employee import is not reachable" as a fact instead of discovering it.
 */
export const teamStrings: Namespace = {
  title: { ar: 'الفريق', en: 'Team' },
  description: {
    ar: 'من في هذه المؤسسة، بدوره ومشاريعه وحساباته المفتوحة.',
    en: 'Who is in this organization, their role and projects, and their open sessions.',
  },
  refresh: { ar: 'تحديث', en: 'Refresh' },
  unavailableNote: {
    ar: 'هذه الشاشة تقرأ سجل الهوية الإداري. لا يوجد مسار لساعات العمل أو التوزيع أو الاستيراد في العقد الموقّع.',
    en: 'This screen reads the administration directory. The published contract has no route for working hours, routing or employee import.',
  },

  // -- summary --------------------------------------------------------------
  'summary.totalUsers': { ar: 'المستخدمون', en: 'Users' },
  'summary.inactiveUsers': { ar: 'موقوفون', en: 'Inactive' },
  'summary.administrators': { ar: 'مسؤولون', en: 'Administrators' },
  'summary.liveSessions': { ar: 'جلسات مفتوحة', en: 'Live sessions' },
  'summary.memberships': { ar: 'العضويات', en: 'Memberships' },
  'summary.projects': { ar: 'المشاريع', en: 'Projects' },
  'summary.archivedProjects': { ar: 'مشاريع مؤرشفة', en: 'Archived projects' },

  // -- table ----------------------------------------------------------------
  'table.name': { ar: 'الاسم', en: 'Name' },
  'table.email': { ar: 'البريد الإلكتروني', en: 'Email' },
  'table.role': { ar: 'الدور', en: 'Role' },
  'table.status': { ar: 'الحالة', en: 'Status' },
  'table.projects': { ar: 'المشاريع', en: 'Projects' },
  'table.sessions': { ar: 'الجلسات', en: 'Sessions' },
  'table.lastLogin': { ar: 'آخر دخول', en: 'Last sign-in' },
  'table.active': { ar: 'نشط', en: 'Active' },
  'table.inactive': { ar: 'موقوف', en: 'Inactive' },
  'table.membershipCount': {
    ar: '{{count}} مشروع',
    en: '{{count}} project(s)',
  },
  'table.empty': { ar: 'لا يوجد مستخدمون', en: 'No users' },
  'table.emptyBody': {
    ar: 'لا يظهر أي مستخدم في هذا النطاق.',
    en: 'No user appears in this scope.',
  },

  // -- permissions inspection ----------------------------------------------
  'permissions.title': { ar: 'صلاحيات مستخدم', en: 'A user’s permissions' },
  'permissions.hint': {
    ar: 'هذه إجابة الخادم، وليست تخميناً من اسم الدور.',
    en: 'This is the server’s answer, not a guess made from the role name.',
  },
  'permissions.granted': { ar: 'ممنوحة', en: 'Granted' },
  'permissions.missing': { ar: 'ناقصة', en: 'Missing' },
  'permissions.summary': {
    ar: '{{granted}} من {{total}} صلاحية ممنوحة.',
    en: '{{granted}} of {{total}} permissions granted.',
  },
  // Counts, not a ratio: `PermissionInspection` publishes no total, so a client
  // that divided by one would be asserting a figure the server never sent.
  'permissions.grantedCount': {
    ar: '{{count}} ممنوحة',
    en: '{{count}} granted',
  },
  'permissions.missingCount': {
    ar: '{{count}} ناقصة',
    en: '{{count}} missing',
  },
  'permissions.none': {
    ar: 'لا توجد صلاحيات ناقصة.',
    en: 'No permissions are missing.',
  },

  // -- sessions -------------------------------------------------------------
  'sessions.title': { ar: 'الجلسات المفتوحة', en: 'Live sessions' },
  'sessions.id': { ar: 'الجلسة', en: 'Session' },
  'sessions.user': { ar: 'المستخدم', en: 'User' },
  'sessions.agent': { ar: 'الوكيل', en: 'User agent' },
  'sessions.address': { ar: 'العنوان', en: 'Address' },
  'sessions.created': { ar: 'بدأت', en: 'Started' },
  'sessions.expires': { ar: 'تنتهي', en: 'Expires' },
  'sessions.empty': { ar: 'لا توجد جلسات مفتوحة', en: 'No live sessions' },
  'sessions.emptyBody': {
    ar: 'لا توجد جلسة نشطة الآن.',
    en: 'No session is active right now.',
  },

  // -- directory extras -----------------------------------------------------
  'servicePrincipals.title': { ar: 'الحسابات الخدمية', en: 'Service principals' },
  'servicePrincipals.hint': {
    ar: 'الحساب الخدّي الذي أرسل الطلب، كما سجّله الخادم.',
    en: 'The service principal that made the request, as the server recorded it.',
  },
  'servicePrincipals.type': { ar: 'النوع', en: 'Type' },
  'servicePrincipals.name': { ar: 'الاسم', en: 'Name' },
  'servicePrincipals.seen': { ar: 'آخر ظهور', en: 'Last seen' },
  'servicePrincipals.occurrences': { ar: 'مرات', en: 'Occurrences' },
  'servicePrincipals.empty': { ar: 'لا حسابات خدمية مرصودة', en: 'No observed principals' },
  'servicePrincipals.notAdministrative': { ar: 'غير إداري', en: 'Not administrative' },
  'servicePrincipals.administrative': { ar: 'إداري', en: 'Administrative' },

  'credentials.title': { ar: 'الاتصالات وبيانات الاعتماد', en: 'Connections and credentials' },
  'credentials.hint': {
    ar: 'نُدرج وجود بيانات الاعتماد فقط، ولا تُعرض قيمها أبداً.',
    en: 'Only the presence of credentials is listed; their values are never shown.',
  },
  'credentials.channel': { ar: 'القناة', en: 'Channel' },
  'credentials.name': { ar: 'الاسم', en: 'Name' },
  'credentials.status': { ar: 'الحالة', en: 'Status' },
  'credentials.present': { ar: 'موجودة', en: 'Present' },
  'credentials.absent': { ar: 'غير موجودة', en: 'Absent' },
  'credentials.secret': { ar: 'سر الـwebhook', en: 'Webhook secret' },
  'credentials.empty': { ar: 'لا اتصالات', en: 'No connections' },

  // -- not available --------------------------------------------------------
  'unavailable.title': { ar: 'ساعات العمل والتوزيع والاستيراد', en: 'Working hours, routing and import' },
  'unavailable.body': {
    ar: 'لا يوجد مسار في العقد الموقّع لهذه القدرات، فلا يمكن عرضها أو تعديلها. لن نعرض نموذجاً يرفضه الخادم.',
    en: 'The published contract has no route for these capabilities, so they cannot be shown or changed. No form is offered that the server would refuse.',
  },

  'role.ADMIN': { ar: 'مسؤول', en: 'Admin' },
  'role.EMPLOYEE': { ar: 'موظف', en: 'Employee' },

  'principalType.USER': { ar: 'مستخدم', en: 'User' },
  'principalType.SERVICE': { ar: 'خدمة', en: 'Service' },
  'principalType.CONNECTOR': { ar: 'موصّل', en: 'Connector' },
  'principalType.WORKER': { ar: 'عامل', en: 'Worker' },
  'principalType.AI_AGENT': { ar: 'وكيل ذكاء اصطناعي', en: 'AI agent' },
} as const;
