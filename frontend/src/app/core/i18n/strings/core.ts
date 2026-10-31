/**
 * The `shell` namespace.
 *
 * Keys are owned per route and merged into the global dictionary at
 * registration, so a lazy route owns its own copy and nothing else has to know
 * about it. Arabic is written first.
 */
import type { Namespace } from '../i18n.types';

export const shellStrings: Namespace = {
  'app.name': { ar: 'كلينك فلو', en: 'Clinic Flow' },
  'app.short': { ar: 'CF', en: 'CF' },

  'nav.dashboard': { ar: 'اللوحة الرئيسية', en: 'Dashboard' },
  'nav.appointments': { ar: 'قائمة المواعيد', en: 'Appointment List' },
  'nav.patients': { ar: 'المرضى', en: 'Patient' },
  'nav.prescriptions': { ar: 'الوصفات الطبية', en: 'Prescriptions' },
  'nav.lab': { ar: 'المختبر', en: 'Lab' },
  'nav.aligners': { ar: 'مختبر التقويم الشفاف', en: 'Clear Aligner Lab' },
  'nav.clinics': { ar: 'العيادات والفروع', en: 'Clinics' },
  'nav.services': { ar: 'الخدمات والإجراءات', en: 'Services' },
  'nav.feedback': { ar: 'تقييمات المرضى', en: 'Patient Feedback' },
  'nav.doctors': { ar: 'الأطباء', en: 'Doctors' },
  'nav.doctorSession': { ar: 'جلسات الأطباء', en: 'Doctor Session' },
  'nav.frontDesk': { ar: 'موظف الاستقبال', en: 'Front Desk Officer' },
  'nav.billingRecord': { ar: 'سجل الفواتير', en: 'Billing Record' },
  'nav.doctorCommission': { ar: 'عمولة الأطباء', en: 'Doctor Commission' },
  'nav.doctorPayout': { ar: 'مستحقات الأطباء', en: 'Doctor Payout' },
  'nav.revenueBreakdown': { ar: 'تفصيل الإيرادات', en: 'Revenue Breakdown' },
  'nav.appointmentOverview': { ar: 'نظرة عامة على المواعيد', en: 'Appointment Overview' },
  'nav.clinicOverview': { ar: 'نظرة عامة على العيادة', en: 'Clinic Overview' },
  'nav.postInstructions': { ar: 'تعليمات ما بعد المعالجة', en: 'Post Instructions' },
  'nav.systemSetting': { ar: 'إعدادات النظام', en: 'System Setting' },

  'nav.groupClinicCenters': { ar: 'مراكز العيادة', en: 'CLINIC CENTERS' },
  'nav.groupUsers': { ar: 'المستخدمون', en: 'USERS' },
  'nav.groupFinance': { ar: 'المالية', en: 'FINANCE' },
  'nav.groupSystem': { ar: 'النظام', en: 'SYSTEM' },
  'nav.groupPlatform': { ar: 'إدارة المنصة والعيادات', en: 'PLATFORM OPERATIONS' },
  'nav.platformDashboard': { ar: 'لوحة تحكم المنصة', en: 'Platform Dashboard' },
  'nav.platformClinics': { ar: 'العيادات وحسابات الأدمن', en: 'Clinics & Admins' },
  'nav.platformSubscriptions': { ar: 'الاشتراكات والفوترة', en: 'Subscriptions & Billing' },
  'nav.platformPlans': { ar: 'الباقات والحدود', en: 'Plans & Limits' },
  'nav.platformAudit': { ar: 'سجل التدقيق العام', en: 'Platform Audit' },
  'shell.nav.groupPlatform': { ar: 'إدارة المنصة والعيادات', en: 'PLATFORM OPERATIONS' },
  'shell.nav.platformDashboard': { ar: 'لوحة تحكم المنصة', en: 'Platform Dashboard' },
  'shell.nav.platformClinics': { ar: 'العيادات والمشرفين', en: 'Clinics & Admins' },
  'shell.nav.platformSubscriptions': { ar: 'الاشتراكات والفوترة', en: 'Subscriptions & Billing' },
  'shell.nav.platformPlans': { ar: 'الباقات والحدود', en: 'Plans & Limits' },
  'shell.nav.platformAudit': { ar: 'سجل التدقيق الأمني', en: 'Platform Audit' },
  'nav.subscriptions': { ar: 'الاشتراكات والمدفوعات', en: 'Subscriptions & Payments' },
  'nav.plansAndLimits': { ar: 'الباقات والحدود', en: 'Plans & Limits' },
  'nav.supportAccess': { ar: 'وصول الدعم الفني', en: 'Support Access' },
  'nav.system': { ar: 'إدارة النظام', en: 'System' },
  'nav.auditTrail': { ar: 'سجل التدقيق', en: 'Audit trail' },
  'nav.governance': { ar: 'الحوكمة القانونية', en: 'Legal governance' },
  'nav.topEncounter': { ar: 'الزيارات السريرية', en: 'Encounter' },

  'nav.inbox': { ar: 'الوارد', en: 'Inbox' },
  'nav.people': { ar: 'الأشخاص', en: 'People' },
  'nav.team': { ar: 'الفريق', en: 'Team' },
  'nav.settings': { ar: 'الإعدادات', en: 'Settings' },
  'nav.admin': { ar: 'الإدارة', en: 'Administration' },
  'nav.finance': { ar: 'المالية', en: 'Finance' },
  'nav.exports': { ar: 'التقارير والتصدير', en: 'Reports & exports' },
  'nav.clinic': { ar: 'العيادة', en: 'Clinic' },
  'nav.clinicPatients': { ar: 'المرضى', en: 'Patients' },
  'nav.clinicCalendar': { ar: 'المواعيد والتقويم', en: 'Calendar' },
  'nav.clinicSchedules': { ar: 'الكادر وجداول العمل', en: 'Roster & Schedules' },
  'nav.clinicServices': { ar: 'الإجراءات والخدمات', en: 'Services & Procedures' },
  'nav.clinicInvoices': { ar: 'الفواتير والمطالبات', en: 'Invoices' },
  'nav.clinicPayments': { ar: 'سندات القبض والدفعات', en: 'Payments' },
  'nav.clinicReports': { ar: 'التقارير السريرية والمالية', en: 'Clinic Reports' },
  'nav.clinicAi': { ar: 'المساعد الذكي للعيادة', en: 'Clinic AI' },
  'nav.clinicPolicies': { ar: 'سياسات العيادة', en: 'Clinic Policies' },
  'nav.knowledge': { ar: 'المعرفة والوكيل', en: 'Knowledge & agent' },
  'nav.channels': { ar: 'القنوات', en: 'Channels' },
  'nav.audit': { ar: 'سجل التدقيق', en: 'Audit trail' },

  /**
   * Why a destination is unavailable.
   *
   * Each one names the actual missing read model rather than saying "coming soon",
   * because "coming soon" is a promise and this is a fact: the committed contract
   * has no route for it. The operator can see that without reading the release
   * notes, and nobody is misled into waiting for a screen that needs a server
   * change first.
   */
  'nav.unavailable.clinic': {
    ar: 'لا يوجد مسار للعيادة في العقد الموقّع. المواعيد تظهر ضمن ملف العميل فقط.',
    en: 'There is no clinic route in the published contract. Appointments appear inside a customer record only.',
  },
  'nav.unavailable.availability': {
    ar: 'لا يوجد مسار لساعات العمل أو التوزيع في العقد الموقّع.',
    en: 'There is no working-hours or routing route in the published contract.',
  },
  'nav.unavailable.aiAgents': {
    ar: 'لا يوجد مسار لوكلاء الذكاء الاصطناعي في العقد الموقّع. تتوفر سياسة الأدوات والموازنات فقط.',
    en: 'There is no AI-agent route in the published contract. Tool policy and budgets are available.',
  },
  'nav.unavailable.escalation': {
    ar: 'لا يوجد مسار للتصعيدات. التصعيد حقل ضمن تسجيل الحل، لا قائمة عمل.',
    en: 'There is no escalation route. Escalation is a field on a resolution, not a queue.',
  },
  'nav.unavailable.finance': {
    ar: 'لا يوجد في صلاحيات النظام إذن يخوّل الوصول إلى المالية، فتبقى الشاشة غير معروضة.',
    en: 'No permission in the system grants access to finance, so the screen stays unlinked.',
  },
  'nav.unavailable.exports': {
    ar: 'لا يوجد في صلاحيات النظام إذن يخوّل الوصول إلى التصدير، فتبقى الشاشة غير معروضة.',
    en: 'No permission in the system grants access to exports, so the screen stays unlinked.',
  },

  'nav.primary': { ar: 'التنقل الرئيسي', en: 'Main navigation' },
  'nav.groups': { ar: 'أقسام المنتج', en: 'Product sections' },
  'nav.govern': { ar: 'الحوكمة', en: 'Governance' },
  'nav.modeToday': { ar: 'اليوم والجدول', en: 'Today & Schedule' },
  'nav.modePatients': { ar: 'المرضى والملف السريري', en: 'Patients & Clinical' },
  'nav.modeManagement': { ar: 'إدارة الممارسة والمالية', en: 'Practice & Management' },
  'nav.collapse': { ar: 'طي القائمة', en: 'Collapse navigation' },
  'nav.expand': { ar: 'توسيع القائمة', en: 'Expand navigation' },
  'nav.openMenu': { ar: 'فتح القائمة', en: 'Open navigation' },

  // The account menu's quick views. They were hard-coded English, in a shell
  // whose whole purpose is to be readable in both directions.
  'nav.quickViews': { ar: 'اختصارات سريعة', en: 'Quick views' },
  'nav.quickView.admin': { ar: 'عرض إدارة العيادة', en: 'Clinic admin view' },
  'nav.quickView.reception': { ar: 'مسار الاستقبال', en: 'Receptionist flow' },
  'nav.quickView.doctor': { ar: 'الطبيب وسجل الأسنان', en: 'Doctor & odontogram' },
  'nav.quickView.ai': { ar: 'لوحة تحكم الوكيل الذكي', en: 'AI agent control panel' },
  'nav.quickView.platform': { ar: 'إدارة الموقع', en: 'Website admin' },

  // Why a navigation item is not a link yet. Stated in words because a control
  // that cannot be used with no explanation is worse than one that is absent
  // (`docs/migration-AGENT.md` §4.5 on disabled states).
  'nav.soon': {
    ar: 'لا يوجد مسار في العقد الموقّع لهذه الشاشة.',
    en: 'The published contract has no route for this screen.',
  },

  // Context the header carries. The keys are unprefixed inside the `shell`
  // namespace because the merged key is already `shell.projectScope` — writing
  // `shell.shell.projectScope` would read like a mistake even though it resolves.
  'workspace': { ar: 'مساحة العمل', en: 'Workspace' },
  'organization': { ar: 'المنشأة', en: 'Organization' },
  'projectAll': { ar: 'كل المشاريع', en: 'All projects' },
  'projectSelect': { ar: 'اختيار المشروع', en: 'Choose a project' },
  'projectScope': {
    ar: 'المشاريع المتاحة لك',
    en: 'Projects available to you',
  },
  'projectCount': {
    ar: '{{count}} مشروع متاح',
    en: '{{count}} project(s) available',
  },
  'projectNone': {
    ar: 'لم تُسنَد إليك أي مشروع بعد. تواصل مع المسؤول.',
    en: 'You are not assigned to any project yet. Ask an admin to add you.',
  },
  'projectById': {
    ar: 'مشروع {{id}}',
    en: 'Project {{id}}',
  },

  'account.signOut': { ar: 'خروج', en: 'Sign out' },
  'account.signOutPending': { ar: 'جارٍ الخروج…', en: 'Signing out…' },
  'account.menu': { ar: 'حسابك', en: 'Your account' },
  'account.role': { ar: 'الدور', en: 'Role' },
  'account.email': { ar: 'البريد الإلكتروني', en: 'Email' },
  'account.sessionEnded': {
    ar: 'انتهت الجلسة. سجّل الدخول من جديد للمتابعة.',
    en: 'Your session ended. Sign in again to continue.',
  },
  'account.sessionRevoked': {
    ar: 'أُنهيت الجلسة من مكان آخر. سجّل الدخول من جديد للمتابعة.',
    en: 'Your session was ended elsewhere. Sign in again to continue.',
  },
  'account.sessionFirst': {
    ar: 'سجّل الدخول للمتابعة.',
    en: 'Sign in to continue.',
  },

  'role.ADMIN': { ar: 'مسؤول', en: 'Admin' },
  'role.EMPLOYEE': { ar: 'موظف', en: 'Employee' },
  /**
   * The front desk. Its label is here rather than derived from the role constant,
   * because a role with no label renders as the raw key — and the front desk is
   * the role the clinic exists for, so it is the last one that may look broken.
   */
  'role.CLINIC_STAFF': { ar: 'موظف استقبال', en: 'Front desk' },

  // Conversation states, from the `Status` vocabulary the CRM uses. They live in
  // the core namespace because the dashboard funnel, the queue and the inbox all
  // read them, and a state whose label is missing renders as its raw key — which
  // is what happened before this block existed.
  'shell.status.OPEN': { ar: 'مفتوحة', en: 'Open' },
  'shell.status.PENDING': { ar: 'معلّقة', en: 'Pending' },
  'shell.status.RESOLVED': { ar: 'محلولة', en: 'Resolved' },
  'shell.status.CLOSED': { ar: 'مغلقة', en: 'Closed' },

  // Channels, from the connector vocabulary. A channel this list does not name
  // falls back to its own wire value rather than to nothing.
  'shell.channel.MOCK': { ar: 'تجريبي', en: 'Mock' },
  'shell.channel.WHATSAPP': { ar: 'واتساب', en: 'WhatsApp' },
  'shell.channel.INSTAGRAM': { ar: 'إنستغرام', en: 'Instagram' },
  'shell.channel.MESSENGER': { ar: 'ماسنجر', en: 'Messenger' },
  'shell.channel.TELEGRAM': { ar: 'تيليغرام', en: 'Telegram' },
  'shell.channel.WEB': { ar: 'الموقع', en: 'Web' },
  'shell.channel.EMAIL': { ar: 'البريد', en: 'Email' },

  'theme.toggle': { ar: 'تبديل المظهر', en: 'Switch theme' },
  'theme.light': { ar: 'فاتح', en: 'Light' },
  'theme.dark': { ar: 'داكن', en: 'Dark' },
  'theme.current': { ar: 'المظهر الحالي', en: 'Current theme' },

  'locale.toggle': { ar: 'تغيير اللغة', en: 'Change language' },
  'locale.arabic': { ar: 'English', en: 'العربية' },
  'locale.english': { ar: 'العربية', en: 'English' },
  'locale.current': { ar: 'اللغة الحالية', en: 'Current language' },

  'notifications.title': { ar: 'الإشعارات', en: 'Notifications' },
  'notifications.open': { ar: 'فتح الإشعارات', en: 'Open notifications' },
  'notifications.empty': { ar: 'لا إشعارات', en: 'No notifications' },
  'notifications.markAll': { ar: 'تعليم الكل كمقروء', en: 'Mark all read' },
  'notifications.unread': {
    ar: '{{count}} إشعار غير مقروء',
    en: '{{count}} unread notification(s)',
  },
  'notifications.read': { ar: 'مقروء', en: 'Read' },

  'realtime.connected': { ar: 'متصل مباشرة', en: 'Live' },
  'realtime.reconnecting': { ar: 'جارٍ إعادة الاتصال…', en: 'Reconnecting…' },
  'realtime.offline': { ar: 'لا يوجد اتصال مباشر', en: 'No live connection' },
  'realtime.status': { ar: 'حالة الاتصال المباشر', en: 'Live connection status' },
  'realtime.resynced': {
    ar: 'تمت المزامنة من الخادم',
    en: 'Resynchronised from the server',
  },
  'realtime.stale': {
    ar: 'البيانات قد تكون قديمة',
    en: 'This data may be out of date',
  },
  'realtime.resync': { ar: 'مزامنة الآن', en: 'Resync now' },
} as const;
