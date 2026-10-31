/**
 * Group 9 — the administration screen.
 *
 * Arabic first in every pair, English second, and the two must never be the same
 * string: `i18n.spec.ts` asserts that across every registered namespace, so a
 * copy-paste that leaves the English as Arabic fails the suite rather than
 * shipping an untranslated screen.
 *
 * ## What the screen is allowed to say
 *
 * Two rules shape the copy throughout:
 *
 * - **A missing route is named.** `unavailable.*` strings carry the route in the
 *   text so an operator can file it and a reviewer can check it. "Coming soon"
 *   would be a lie about a date nobody committed to.
 * - **A destructive act is described as a command.** The seven governance writes
 *   are not rendered as buttons; the strings that *would* label them are still
 *   declared, because the decision not to ship them is a decision a reviewer needs
 *   to be able to read, and the next reviewer needs the vocabulary ready.
 */
export const adminStrings = {
  'title': { ar: 'الإدارة', en: 'Administration' },
  'description': {
    ar: 'حالة النظام والنسخ الاحتياطية وسجل التدقيق، والحجوزات القانونية التي توجّه الاحتفاظ بالبيانات.',
    en: 'System health, backups, the audit trail, and the legal holds that direct retention.',
  },
  'refresh': { ar: 'تحديث', en: 'Refresh' },

  // -- overview -------------------------------------------------------------
  'overview.title': { ar: 'نظرة عامة', en: 'Overview' },
  'overview.hint': {
    ar: 'حالة الخدمة ومؤشراتها كما يقرؤها الخادم.',
    en: 'Service health and its counters, as the server reports them.',
  },
  'overview.empty': {
    ar: 'لم يُرجِع الخادم أي مؤشرات.',
    en: 'The server returned no counters.',
  },
  'overview.emptyBody': {
    ar: 'النقطة نفسها تجيب، لكن ليس فيها ما يُعرض. هذا ليس صفراً.',
    en: 'The endpoint itself answers, but there is nothing in it to show. That is not a zero.',
  },

  // -- backups --------------------------------------------------------------
  'backups.title': { ar: 'النسخ الاحتياطية', en: 'Backups' },
  'backups.hint': {
    ar: 'ما يحتفظ به الخادم من نسخ، وكم لقطة.',
    en: 'The snapshots the server holds, and how many there are.',
  },
  'backups.empty': {
    ar: 'لا توجد نسخ احتياطية مسجّلة.',
    en: 'No backups are recorded.',
  },
  'backups.emptyBody': {
    ar: 'الخدمة تعمل بلا نسخة. هذه حالة تستحق قراراً، لا شاشة فارغة.',
    en: 'The service is running with no backup. That is a state to decide about, not an empty screen.',
  },

  // -- migrations and maintenance ------------------------------------------
  'migrations.title': { ar: 'ترحيلات القاعدة', en: 'Database migrations' },
  'migrations.hint': {
    ar: 'ماطبَّق على القاعدة، وبأي إصدار.',
    en: 'What has been applied to the database, and at which version.',
  },
  'migrations.empty': {
    ar: 'لا توجد ترحيلات مسجّلة.',
    en: 'No migrations are recorded.',
  },
  'commands.title': { ar: 'أوامر الصيانة', en: 'Maintenance commands' },
  'commands.hint': {
    ar: 'الأوامر التي يوفّرها الخادم. كل واحد منها مُسمّى ومُدقَّق ومسجَّل.',
    en: 'The commands the server offers. Each one is named, validated and audited.',
  },
  'commands.empty': {
    ar: 'لا توجد أوامر متاحة.',
    en: 'No commands are available.',
  },
  'commands.disabled': {
    ar: 'معطّلة في هذه البيئة',
    en: 'Disabled in this environment',
  },

  // -- audit ----------------------------------------------------------------
  'audit.title': { ar: 'سجل التدقيق', en: 'Audit trail' },
  'audit.hint': {
    ar: 'ما جرى، ومَن فعله، ومتى.',
    en: 'What happened, who did it, and when.',
  },
  'audit.empty': {
    ar: 'لا أحداث مسجّلة.',
    en: 'No events are recorded.',
  },
  'audit.security': {
    ar: 'أحداث أمنية',
    en: 'Security events',
  },

  // -- governance -----------------------------------------------------------
  'governance.title': { ar: 'الحوكمة القانونية', en: 'Legal governance' },
  'governance.hint': {
    ar: 'الحجوزات التي تمنع الحذف، وطلبات حذف البيانات، وبوابة التنقية.',
    en: 'The holds that prevent deletion, the deletion requests, and the purge gate.',
  },
  'governance.empty': {
    ar: 'لا شيء محجوز ولا طلبات حذف.',
    en: 'Nothing is held and no deletion is requested.',
  },
  'governance.emptyBody': {
    ar: 'لا توجد حجز قانوني قائم. هذه حالة سليمة، لكنها ليست دليلاً.',
    en: 'No legal hold is in force. That is a healthy state, but it is not evidence.',
  },
  'governance.purgeGate': { ar: 'بوابة التنقية', en: 'Purge gate' },

  // -- sessions -------------------------------------------------------------
  'sessions.title': { ar: 'الجلسات النشطة', en: 'Active sessions' },
  'sessions.hint': {
    ar: 'من متصل الآن، ومنذ متى.',
    en: 'Who is connected, and since when.',
  },
  'sessions.empty': {
    ar: 'لا جلسات نشطة.',
    en: 'No active sessions.',
  },

  // -- export jobs ----------------------------------------------------------
  'jobs.title': { ar: 'مهام التصدير', en: 'Export jobs' },
  'jobs.hint': {
    ar: 'كل مهام التصدير في المؤسسة، لا مشروع واحد.',
    en: 'Every export job in the organization, not one project.',
  },
  'jobs.empty': {
    ar: 'لا مهام تصدير.',
    en: 'No export jobs.',
  },
  'jobs.emptyBody': {
    ar: 'لم يُنشأ أي تصدير بعد، أو انتهت كلها ومُحذفت آثارها.',
    en: 'No export has been created yet, or all of them finished and their artefacts were deleted.',
  },

  // -- what this screen deliberately does not do ----------------------------
  //
  // These are not placeholders for missing routes. The routes exist; the controls do
  // not, on purpose, and the copy says why so the decision is legible from the product
  // rather than only from the repository.
  'writes.title': { ar: 'عمليات لا يقدّمها هذا التطبيق', en: 'Writes this screen does not offer' },
  'writes.body': {
    ar: 'الخادم يوفّر سبع عمليات كتابة للإدارة — إنشاء حجز قانوني أو الإفراج عنه، وطلب حذف بيانات أو معاينته أو تنفيذه، والتنقية. كلها تخضع لصلاحية واحدة هي أوسع صلاحية في النظام. لم يقدّم أيٌّ منها هنا لأن كل واحدة فعل تدمير متعمَّد يُطلب باسمه، ويجانب شارة حالة ضع ذلك عكس ما يُطلب. ما يمكنك رؤيته أدناه هو الحالة التي تغيّرها هذه العمليات.',
    en: 'The server offers seven administrative writes — placing and releasing a legal hold, requesting, previewing and executing a privacy deletion, and purging. They all require the single broadest permission in the system. None is offered here, because each is a deliberate destructive act that should be requested by name, and putting one next to a status chip inverts what should be asked. What you can see below is the state these writes change.',
  },
  'writes.commands': {
    ar: 'الأوامر المتاحة على الخادم:',
    en: 'The commands the server offers:',
  },

  // -- unavailable ----------------------------------------------------------
  'unavailable.title': {
    ar: 'غير متاح في هذه النسخة',
    en: 'Not available in this build',
  },
  'unavailable.body': {
    ar: 'لا يوجد مسار في العقد الموقَّع لهذه الشاشة. الحالة معروضة صراحةً بدلاً من رقم مُختلق أو صف فارغ يبدو كأنه لا شيء.',
    en: 'The signed contract has no route for this surface. The state is stated outright rather than shown as an invented figure or an empty list that looks like nothing.',
  },
  'unavailable.migrations': {
    ar: 'عرض تفاصيل كل ترحيل على حدة',
    en: 'Per-migration detail rows',
  },
  'unavailable.migrationsBody': {
    ar: '‎GET /api/v1/admin/migrations‎ يُرجع حريةً بلا شكل معلن، فلا أعمدةً مضمونة تُعرض منها. القائمة تُقرأ كما وصلت، وكل حقلٍ غائب يظهر كشرطة لا كصفر.',
    en: 'GET /api/v1/admin/migrations returns a free-form document with no declared shape, so there are no guaranteed columns to render. The list is read as it arrived, and every absent field shows as a dash rather than a zero.',
  },
  'unavailable.purge': {
    ar: 'تنقية البيانات',
    en: 'Purging data',
  },
  'unavailable.purgeBody': {
    ar: 'بوابة التنقية تُقرأ، أما التنقية نفسها فلا تقدّم. الحذف النهائي للبيانات يحتاج قراراً صريحاً ورسماً تدقيقياً لا زراً في شاشة.',
    en: 'The purge gate is read; the purge itself is not offered. Permanently destroying records needs an explicit decision and an audit event, not a button on a screen.',
  },
  'unavailable.identityWrites': {
    ar: 'تغيير الدور والعضوية وإنهاء الجلسة',
    en: 'Changing a role, a membership, or revoking a session',
  },
  'unavailable.identityWritesBody': {
    ar: 'هذه المسارات موجودة وتعمل، لكنها ليست في هذه النسخة. تغيير دور مستخدم يُنهي جلساته فعلياً، وهو أثر لا يُطلب بالضغط على زر بين شارة وشارة.',
    en: 'These routes exist and work, but are not in this build. Changing a user\u2019s role actually cuts off their sessions, and that is an effect that should not be one click between two status chips.',
  },
} as const;
