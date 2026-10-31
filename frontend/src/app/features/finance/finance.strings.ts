/**
 * Groups 10 and 11 — finance, reports and exports.
 *
 * Arabic first in every pair. `i18n.spec.ts` asserts across every registered
 * namespace that each key has both languages and that the two differ, so a pair left
 * with the Arabic in the English slot fails rather than shipping.
 *
 * ## Numbers are not rendered here
 *
 * Every figure on this screen comes from the server as a bare JSON number, and this
 * file deliberately carries no currency or number formatting. The formatter
 * (`FormatService`) owns that, with the locale's own numbering system — which is why
 * the Arabic side of this screen is checked by a journey rather than by a snapshot
 * here. A hard-coded thousand separator in a string table is how an Arabic screen ends
 * up with Western digits.
 *
 * ## The write vocabulary is declared but unused
 *
 * The strings that *would* label the eleven finance writes and the export lifecycle
 * commands exist so the decision not to render them is reviewable in the product's own
 * copy, and so the next mission does not have to invent names that then have to be
 * retranslated.
 */
export const financeStrings = {
  'title': { ar: 'التقارير والمالية', en: 'Reports and finance' },
  'description': {
    ar: 'ما كلّفه العمل، وما الذي خرج منه، وحالة كل تصدير.',
    en: 'What the work cost, what came out of it, and the state of every export.',
  },
  'refresh': { ar: 'تحديث', en: 'Refresh' },

  // -- usage ----------------------------------------------------------------
  'usage.title': { ar: 'الاستهلاك', en: 'Usage' },
  'usage.hint': {
    ar: 'الاستهلاك اليومي والشهري والتوقّع، مُسعَّراً من بطاقات الأسعار.',
    en: 'Daily and monthly usage, and the forecast, priced from the rate cards.',
  },
  'usage.day': { ar: 'اليوم', en: 'Day' },
  'usage.project': { ar: 'المشروع', en: 'Project' },
  'usage.input': { ar: 'رموز الإدخال', en: 'Input tokens' },
  'usage.output': { ar: 'رموز الإخراج', en: 'Output tokens' },
  'usage.cost': { ar: 'التكلفة', en: 'Cost' },
  'usage.empty': {
    ar: 'لا يوجد استهلاك مسجّل في هذه الفترة.',
    en: 'No usage is recorded for this period.',
  },
  'usage.emptyBody': {
    ar: 'الاستهلاك صفر حتى الآن، أو لم يُحتسب بعد. الرقم صفر، وهذا ليس غياب بيانات.',
    en: 'Usage is zero so far, or has not been computed yet. That is a zero, and not an absence of data.',
  },

  // -- periods --------------------------------------------------------------
  'periods.title': { ar: 'الفترات المالية', en: 'Accounting periods' },
  'periods.hint': {
    ar: 'حالة كل فترة، وهل أُغلقت أو قُفلت.',
    en: 'Where each period stands, and whether it has been closed or locked.',
  },
  'periods.name': { ar: 'الاسم', en: 'Name' },
  'periods.status': { ar: 'الحالة', en: 'Status' },
  'periods.start': { ar: 'البداية', en: 'Start' },
  'periods.end': { ar: 'النهاية', en: 'End' },
  'periods.adjustments': { ar: 'التسويات', en: 'Adjustments' },
  'periods.empty': {
    ar: 'لا فترات مالية مُعرَّفة.',
    en: 'No accounting periods are defined.',
  },
  'periods.emptyBody': {
    ar: 'لا فترة يمكن إغلاقها. الإغلاق والتقفل يتطلّبان فترة موجودة، وهي تُنشأ على الخادم.',
    en: 'There is no period to close. Closing and locking need a period to exist, and periods are created server-side.',
  },

  // -- rate cards -----------------------------------------------------------
  'rates.title': { ar: 'بطاقات الأسعار', en: 'Rate cards' },
  'rates.hint': {
    ar: 'الأسعار التي بُنيت عليها كل تكلفة أعلاه.',
    en: 'The prices every cost above was computed from.',
  },
  'rates.empty': {
    ar: 'لا بطاقات أسعار.',
    en: 'No rate cards.',
  },

  // -- reconciliation -------------------------------------------------------
  'reconciliation.title': { ar: 'المطابقة', en: 'Reconciliation' },
  'reconciliation.hint': {
    ar: 'مطابقة الفترات المحسوبة بالمصدر.',
    en: 'Matching the computed periods against their source.',
  },
  'reconciliation.empty': {
    ar: 'لم تُشغَّل أي مطابقة.',
    en: 'No reconciliation has been run.',
  },
  'reconciliation.emptyBody': {
    ar: 'تشغيل المطابقة يتطلّب صلاحية مدير مالي، ولم يُطلب هنا.',
    en: 'Running a reconciliation requires a finance administrator, and is not offered here.',
  },

  // -- legacy ---------------------------------------------------------------
  'legacy.title': { ar: 'المقارنة مع الأرقام القديمة', en: 'Legacy comparison' },
  'legacy.hint': {
    ar: 'الفارق بين أرقام النظام القديم والحالية.',
    en: 'The variance between the old system\u2019s figures and the current ones.',
  },
  'legacy.empty': {
    ar: 'لا أرقام قديمة للمقارنة.',
    en: 'There are no legacy figures to compare.',
  },
  'legacy.emptyBody': {
    ar: 'لم تُرحَّل أرقام النظام القديم إلى هذا المشروع، فلا أساس للمقارنة.',
    en: 'No legacy figures were migrated into this project, so there is nothing to compare against.',
  },

  // -- exports --------------------------------------------------------------
  'exports.title': { ar: 'التصدير', en: 'Exports' },
  'exports.hint': {
    ar: 'كل عمليات التصدير في المشروع، وحالة كل واحدة.',
    en: 'Every export for the project, and the state of each.',
  },
  'exports.status': { ar: 'الحالة', en: 'Status' },
  'exports.progress': { ar: 'التقدّم', en: 'Progress' },
  'exports.rows': { ar: 'الصفوف', en: 'Rows' },
  'exports.size': { ar: 'الحجم', en: 'Size' },
  'exports.format': { ar: 'الصيغة', en: 'Format' },
  'exports.created': { ar: 'أُنشئ', en: 'Created' },
  'exports.finished': { ar: 'انتهى', en: 'Finished' },
  'exports.error': { ar: 'الخطأ', en: 'Error' },
  'exports.empty': {
    ar: 'لا عمليات تصدير.',
    en: 'No exports.',
  },
  'exports.emptyBody': {
    ar: 'لم يُنشأ أي تصدير لهذا المشروع بعد.',
    en: 'No export has been created for this project yet.',
  },

  // -- policy ---------------------------------------------------------------
  'policy.title': { ar: 'سياسة التنزيل والاحتفاظ', en: 'Download and retention policy' },
  'policy.hint': {
    ar: 'ما يُسمح بنزوله، وكم يُحتفظ به قبل الحذف.',
    en: 'What may be downloaded, and how long it is kept before deletion.',
  },
  'policy.empty': {
    ar: 'الخادم لم يصف سياسة.',
    en: 'The server described no policy.',
  },
  'policy.emptyBody': {
    ar: 'الوثيقة المُرجَعة فارغة. هذه ليست سياسة تسمح ولا سياسة تمنع.',
    en: 'The returned document is empty. That is neither a policy that allows nor one that forbids.',
  },

  // -- catalogue ------------------------------------------------------------
  'catalogue.title': { ar: 'دليل ما يمكن تصديره', en: 'What can be exported' },
  'catalogue.hint': {
    ar: 'التقارير والقوالب التي يوفّرها الخادم للتصدير.',
    en: 'The reports and templates the server offers for export.',
  },
  'catalogue.formats': { ar: 'الصيغ المتاحة', en: 'Formats offered' },
  'catalogue.definitionsCount': { ar: 'تعريفات التقارير', en: 'Report definitions' },
  'catalogue.templatesCount': { ar: 'قوالب المستندات', en: 'Document templates' },
  'catalogue.empty': {
    ar: 'الدليل فارغ.',
    en: 'The catalogue is empty.',
  },
  'catalogue.emptyBody': {
    ar: 'لا يوجد هنا ما يمكن تصديره. هذا ما يقوله الخادم، لا فشل في العرض.',
    en: 'Nothing here can be exported. That is what the server reports, not a client failure.',
  },
  'catalogue.versions': { ar: 'النسخ', en: 'Versions' },

  // -- gate -----------------------------------------------------------------
  'gate.title': { ar: 'بوابة المالية على التصدير', en: 'Finance gate on export' },
  'gate.hint': {
    ar: 'هل يسمح للبيانات المصدَّرة أن تحمل أرقاماً مالية.',
    en: 'Whether exported data is allowed to carry financial figures.',
  },
  'gate.organizationOnly': {
    ar: 'هذه البوابة على مستوى المؤسسة، وصلاحيتك لا تشملها.',
    en: 'This gate is organization-wide and your permission does not cover it.',
  },

  // -- what this screen does not do -----------------------------------------
  'writes.title': { ar: 'عمليات لا يقدّمها هذا التطبيق', en: 'Writes this screen does not offer' },
  'writes.body': {
    ar: 'يوفّر الخادم إحدى عشرة عملية كتابة مالية، وهي حساب تدحرج، وإنشاء فترة، ومراجعتها وإعادتها للمراجعة، وإقفالها وإعادة فتحها، وإنشاء تسوية والموافقة عليها ورفضها وتطبيقها، وتشغيل مطابقة. تشترط الخدمة إضافةً إلى الصلاحية أن يكون المتصل مديراً مالياً، فهي أعمال مدير لا أعمال عادية. ولم يقدّم أيٌّ منها هنا لأن كل واحدة تُغيّر أرقاماً مُثبَّتة، وتستحق أن تُطلب باسمها.',
    en: 'The server offers eleven finance writes — computing a rollup, creating a period, reviewing it and returning it to review, locking and reopening it, creating an adjustment and approving, rejecting or applying it, and running a reconciliation. Beyond the permission, the service requires an ADMIN role: these are administrator acts, not ordinary ones. None is offered here, because each one changes figures already fixed, and that should be asked for by name.',
  },
  'exportsWrites': {
    ar: 'عمليات التصدير نفسها — الإنشاء، وإعادة المحاولة، والإلغاء، وتنزيل الأثر، وحذفه، وانتهاء الاحتفاظ — ليست أزراراً هنا. إنشاء تصدير يتطلّب معرّف تعريف تقرير ونسخته، ومعرّف قالب مستند ونسخته، ولا يوجد مسار يسرد التقارير ولا القوالب، فلا يوجد ما تختار منه أصلاً.',
    en: 'The export lifecycle itself — creating, retrying, cancelling, downloading the artefact, deleting it, and retention expiry — is not a set of buttons here. Creating an export needs a report definition id and version, and a document template id and version, and no route lists reports or templates, so there is nothing to pick from in the first place.',
  },
  'writes.purge': { ar: 'التنقية', en: 'Purge' },
  'writes.lock': { ar: 'إقفال فترة', en: 'Lock a period' },
  'writes.reopen': { ar: 'إعادة فتح فترة', en: 'Reopen a period' },
  'writes.approve': { ar: 'الموافقة على تسوية', en: 'Approve an adjustment' },
  'writes.apply': { ar: 'تطبيق تسوية', en: 'Apply an adjustment' },
  'writes.compute': { ar: 'حساب تدحرج', en: 'Compute a rollup' },

  // -- unavailable ----------------------------------------------------------
  // -- creation -------------------------------------------------------------
  'create.choose': { ar: 'اختر…', en: 'Choose…' },
  'create.chooseVersion': { ar: 'اختر نسخة…', en: 'Choose a version…' },
  'create.chooseFormat': { ar: 'اختر صيغة…', en: 'Choose a format…' },
  'create.chooseLocale': { ar: 'اختر لغة…', en: 'Choose a language…' },
  'create.jobId': { ar: 'معرّف المهمة', en: 'Job id' },
  'create.status': { ar: 'الحالة', en: 'Status' },
  'create.withheldColumns': { ar: 'أعمدة محجوبة', en: 'Withheld columns' },
  'create.title': { ar: 'إنشاء تصدير', en: 'Create an export' },
  'create.description': {
    ar: 'يُنشأ التصدير من دليل ما يوفّره الخادم لهذا المشروع: تختار تعريف تقرير ونسخته، وقالب مستند ونسخته.',
    en: "An export is made from the catalogue the server holds for this project: choose a report definition and its version, and a document template and its version.",
  },
  'create.needsProject': {
    ar: 'اختر مشروعاً 먼저. الدليل يُقرأ لمشروع بعينه.',
    en: 'Choose a project first. The catalogue is read for one project.',
  },
  'create.report': { ar: 'تعريف التقرير', en: 'Report definition' },
  'create.reportVersion': { ar: 'نسخة التقرير', en: 'Report version' },
  'create.template': { ar: 'قالب المستند', en: 'Document template' },
  'create.templateVersion': { ar: 'نسخة القالب', en: 'Template version' },
  'create.format': { ar: 'الصيغة', en: 'Format' },
  'create.locale': { ar: 'لغة الملف', en: 'Artefact language' },
  'create.localeHint': {
    ar: 'لغة التسميات والتواريخ والأرقام داخل الملف المُنشأ، وهي مستقلة عن لغة هذه الشاشة.',
    // No apostrophe anywhere in this literal, deliberately: the e2e copy table is an
    // object literal of single-quoted strings, and a message containing an apostrophe
    // has to be escaped in two places to say the same thing twice. Rewording one
    // sentence is cheaper than an escape that the next translator will forget.
    en: 'The language of the labels, dates and numbers inside the generated file, which is independent of the language used on screen.',
  },
  'create.filters': { ar: 'عوامل التصفية', en: 'Filters' },
  'create.filtersHint': {
    ar: 'الحقول التي يوفّرها الدليل فقط. أي مفتاح غيرها يُرفض.',
    en: 'Only the fields the catalogue declares. Any other key is refused.',
  },
  'create.needsChoices': {
    ar: 'اختر تعريف تقرير ونسخته، وقالب مستند ونسخته، قبل الإنشاء.',
    en: 'Choose a report definition and its version, and a document template and its version, before creating.',
  },
  'create.submit': { ar: 'أنشئ التصدير', en: 'Create export' },
  'create.submitting': { ar: 'جارٍ الإنشاء…', en: 'Creating…' },
  'create.queued': {
    ar: 'قبل الخادم التصدير. ستظهر حالته في القائمة أدناه.',
    en: 'The server accepted the export. Its progress is in the list below.',
  },
  'create.formatRefused': {
    ar: 'رفض الخادم هذه الصيغة. اختر صيغة أخرى من الدليل.',
    en: 'The server refused this format. Choose another one from the catalogue.',
  },
  'create.rejected': {
    ar: 'رُفض الطلب. راجع الحقول وأعد المحاولة.',
    en: 'The request was refused. Check the fields and try again.',
  },
  'create.required': { ar: 'هذا الحقل مطلوب.', en: 'This field is required.' },
  'create.versionExportable': { ar: 'متاحة للتصدير', en: 'Available to export' },
  'create.versionWithheld': { ar: 'غير متاحة للتصدير', en: 'Not available to export' },
  'create.versionExportableBody': {
    ar: 'هذه النسخة منشورة، ويمكن تثبيتها في تصدير.',
    en: 'This version is published, and an export may pin it.',
  },
  'create.versionWithheldBody': {
    ar: 'لم يردّ الخادم على سبب، فهذه النسخة غير متاحة للتصدير الآن.',
    en: 'The server gave no reason, so this version is not available to export.',
  },  'create.versionRequired': { ar: 'اختر نسخة.', en: 'Choose a version.' },
  'create.withheld': {
    ar: 'غير متاحة لك',
    en: 'Not available to you',
  },
  'create.withheldHint': {
    ar: 'هذه النسخة محجوبة عن صلاحياتك، والسبب من الخادم:',
    en: 'This version is withheld from your permissions. The server says why:',
  },
  'create.noProject': {
    ar: 'لا يوجد مشروع محدد.',
    en: 'No project is selected.',
  },
  'unavailable.title': {
    ar: 'غير متاح في هذه النسخة',
    en: 'Not available in this build',
  },
  'unavailable.download': {
    ar: 'تنزيل الأثر',
    en: 'Downloading an artefact',
  },
  'unavailable.downloadBody': {
    ar: 'المسار موجود ويحتاج رابطاً مؤقتاً لا يمنحه هذا التطبيق. تنزيل بيانات التصدير فعل يُطلب بالاسم ويسجَّل، ولم يُقدَّم هنا.',
    en: 'The route exists and requires a short-lived link this client does not mint. Downloading exported data is an act to be requested by name and recorded, and it is not offered here.',
  },
  'unavailable.drill': {
    ar: 'الحفر عبر الأرقام',
    en: 'Drill-through on a figure',
  },
  'unavailable.drillBody': {
    ar: 'المسارات موجودة وتعمل، لكنها تحتاج مُعرِّف لقطة أو حدث لم يعرضه أي مسار قائمة. أينما ظهر المعرّف على الشاشة يمكن الحفر منه.',
    en: 'The routes exist and work, but they need a snapshot or event id that no list route publishes. Drill through wherever an id appears on screen.',
  },
  'unavailable.clinic': {
    ar: 'المواعيد والإجراءات',
    en: 'Appointments and procedures',
  },
  'unavailable.clinicBody': {
    ar: 'لا يوجد مسار للمواعيد في العقد الموقَّع. ملخّص الموعد يظهر اليوم داخل سجل العميل فقط. لا رقم مُختلق ولا صف فارغ يُمرَّر على أنه لا مواعيد.',
    en: 'The signed contract has no appointment route. An appointment summary appears today only inside a customer record. No invented figure and no empty list passed off as "no appointments".',
  },
} as const;
