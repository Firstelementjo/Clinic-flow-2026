/**
 * Copy for the report-authoring screen.
 *
 * ## Why the server's own Arabic comes from the server
 *
 * The authoring summary carries a `fields` array where every entry has **both**
 * `labelAr` and `labelEn`, alongside `requiredPermission` and `sensitive`. So the column
 * labels an author picks from are the server's, in both languages, rather than a client
 * translation of 34 field names that would have to be maintained by hand and would drift
 * on the first backend change. This file therefore holds the *chrome* — headings, buttons,
 * refusals — and never the field names.
 */
export const authoringStrings = {
  'title': { ar: 'تأليف التقارير', en: 'Report authoring' },
  'description': {
    ar: 'أنشئ تعريف تقرير أو قالب مستند، ثم انشر نسخة منه. المعرّفات تأتي من ردود الخادم؛ لا يُكتب أي معرّف يدوياً.',
    en: 'Author a report definition or a document template, then publish a version of it. Identifiers come from the server responses; none is typed by hand.',
  },

  // -- the server's own statement -------------------------------------------
  /**
   * The heading for the server's `note`, verbatim.
   *
   * The server sends a sentence explaining that `mayAuthor` is a hint and not an
   * authorization decision. Showing it is the honest thing to do: this screen draws
   * authoring controls from a hint, and an operator deserves to know that the hint is not
   * what enforces anything.
   */
  'serverNote': { ar: 'ما يقوله الخادم', en: 'What the server says' },

  'mayAuthor.title': { ar: 'الصلاحية التي يفحصها الخادم', en: 'The permission the server checks' },
  'mayAuthor.granted': { ar: 'ممنوحة لك', en: 'Granted to you' },
  'mayAuthor.absent': { ar: 'غير ممنوحة لك', en: 'Not granted to you' },
  'mayAuthor.hint': {
    ar: 'هذا تلميح لواجهة العرض فقط. كل عملية تأليف هنا تفحص الصلاحية بنفسها وتردّ برفض لا يكشف شيئاً.',
    en: 'This is a hint for the display only. Every authoring call below checks the permission itself and answers with a non-disclosing refusal.',
  },

  // -- refusals -------------------------------------------------------------
  /**
   * A refusal that is deliberately non-disclosing.
   *
   * The backend answers a caller it will not author for with **404 rather than 403**, so
   * that "you may not author this" and "this is not yours" are indistinguishable from
   * outside. A client that rendered "not found" would be asserting a fact it cannot know;
   * a client that rendered "forbidden" would be asserting the opposite. So this says what
   * is actually true: the server did not return it, and it will not say why.
   *
   * The alternative — probing with a second request to see whether a *different* id
   * succeeds — is not attempted, because it would turn a disclosure defence back into an
   * oracle.
   */
  'refusal.notAvailable': {
    ar: 'لم يردّ الخادم على هذا العنصر. يردّ بالطريقة نفسها سواء لم يوجد أو لم تكن لك صلاحية التأليف، ولذلك لا يستطيع هذا العرض أن يخبرك أيّهما هو.',
    en: 'The server did not return this item. It answers the same way whether it does not exist or you may not author it, so this screen cannot tell you which.',
  },
  'refusal.notAvailableShort': { ar: 'غير متاح', en: 'Not available' },
  'refusal.rejected': {
    ar: 'رفض الخادم الطلب. راجع الحقول وأعد المحاولة.',
    en: 'The server refused the request. Check the fields and try again.',
  },
  'refusal.permission': {
    ar: 'هذه العملية تحتاج صلاحية أخرى.',
    en: 'That operation needs a different permission.',
  },
  'refusal.error': { ar: 'تعذّر إتمام العملية.', en: 'The operation could not be completed.' },

  // -- fields shared by both forms -----------------------------------------
  'field.required': { ar: 'هذا الحقل مطلوب.', en: 'This field is required.' },
  'field.tooShort': { ar: 'القيمة قصيرة جداً.', en: 'This value is too short.' },
  'field.key': { ar: 'المعرّف', en: 'Key' },
  'field.keyHint': {
    ar: 'معرّف ثابت للتقنية، بأحرف لاتينية صغيرة وشرطات.',
    en: 'A stable technical identifier, in lower-case Latin letters and hyphens.',
  },
  'field.nameAr': { ar: 'الاسم بالعربية', en: 'Name in Arabic' },
  'field.nameEn': { ar: 'الاسم بالإنجليزية', en: 'Name in English' },
  'field.nameHint': {
    ar: 'العربية أولاً: تُعرض في المنتجات التي تقرأها بالعربية.',
    en: 'Arabic first: it is what Arabic-reading surfaces show.',
  },
  'field.descriptionAr': { ar: 'الوصف بالعربية', en: 'Description in Arabic' },
  'field.descriptionEn': { ar: 'الوصف بالإنجليزية', en: 'Description in English' },
  'field.timezone': { ar: 'المنطقة الزمنية', en: 'Time zone' },
  'field.locale': { ar: 'اللغة الافتراضية', en: 'Default language' },
  'field.redactionPolicy': { ar: 'سياسة الإخفاء', en: 'Redaction policy' },
  'field.retentionClass': { ar: 'صنف الاحتفاظ', en: 'Retention class' },
  'field.retentionHint': {
    ar: 'مطلوب من الخادم رغم أن العقد يعلنه اختيارياً.',
    en: 'Required by the server, though the contract declares it optional.',
  },
  'field.requiredPermissions': { ar: 'الصلاحيات المطلوبة', en: 'Required permissions' },
  'field.requiredPermissionsHint': {
    ar: 'permission واحدة في كل سطر. لا يلزم أن تمنحها كلها الآن.',
    en: 'One permission per line. You do not have to hold all of them now.',
  },
  'field.subject': { ar: 'المصدر', en: 'Subject' },

  // -- definitions ----------------------------------------------------------
  'definition.title': { ar: 'تعريف تقرير', en: 'A report definition' },
  'definition.submit': { ar: 'أنشئ التعريف', en: 'Create definition' },
  'definition.submitting': { ar: 'جارٍ الإنشاء…', en: 'Creating…' },
  'definition.created': {
    ar: 'أنشأ الخادم التعريف. أضف له نسخة ثم انشرها قبل استخدامها في تصدير.',
    en: 'The server created the definition. Add a version to it and publish that before using it in an export.',
  },
  'definition.list': { ar: 'التعريفات التي يمكنك تأليفها', en: 'Definitions you may author' },
  'rename.title': { ar: 'إعادة تسمية', en: 'Rename' },
  'rename.submit': { ar: 'احفظ الاسم الجديد', en: 'Save the new name' },
  'rename.done': {
    ar: 'حفظ الخادم الاسم الجديد.',
    en: 'The server saved the new name.',
  },
  'rename.versionNote': {
    ar: 'يُرسل الاسم مع إصدار الصف الذي قُرئ. إن غيّره أحدٌ آخر قبلك سيرفض الخادم الكتابة بدلاً من أن يبتلع تغييرك.',
    en: 'The name is sent with the row version this form was read at. If someone else changed it first, the server refuses the write rather than silently overwriting theirs.',
  },
  'definition.listEmpty': {
    ar: 'لا يوجد تعريف بعد. أنشئ واحداً بالأعلى لتبدأ.',
    en: 'No definition yet. Create one above to begin.',
  },
  'definition.key': { ar: 'المعرّف', en: 'Key' },
  'definition.currentVersion': { ar: 'آخر نسخة', en: 'Latest version' },
  'definition.publishedVersion': { ar: 'المنشورة', en: 'Published' },
  'definition.status': { ar: 'الحالة', en: 'Status' },
  'definition.id': { ar: 'المعرّف الفريد', en: 'Identifier' },

  // -- versions -------------------------------------------------------------
  'version.title': { ar: 'نسخ التعريف', en: 'Versions of this definition' },
  'version.author': { ar: 'أضف نسخة', en: 'Add a version' },
  'version.submit': { ar: 'أنشئ النسخة', en: 'Create version' },
  'version.submitting': { ar: 'جارٍ الإنشاء…', en: 'Creating…' },
  'version.created': {
    ar: 'أُنشئت النسخة كمسودة. لا يمكن تثبيتها في تصدير حتى تُنشر.',
    en: 'The version was created as a draft. It cannot be pinned by an export until it is published.',
  },
  'version.changeNote': { ar: 'سبب التغيير', en: 'Why it changed' },
  'version.changeNoteHint': {
    ar: 'يظهر لكل من يقرأ التقرير لاحقاً، فاكتب ما تغيّر لا ما كتبته.',
    en: 'This is what whoever reads the report later sees, so write what changed rather than what you did.',
  },
  'version.columns': { ar: 'الأعمدة', en: 'Columns' },
  'version.columnsHint': {
    ar: 'اختر الأعمدة من حقول المصدر الذي اختاره الخادم.',
    en: 'Choose columns from the fields of the subject the server offered.',
  },
  'version.empty': { ar: 'لا توجد نسخة بعد.', en: 'No version yet.' },
  'version.number': { ar: 'النسخة', en: 'Version' },
  'version.state': { ar: 'الحالة', en: 'State' },
  'version.publishedAt': { ar: 'تاريخ النشر', en: 'Published' },
  'version.retiredAt': { ar: 'تاريخ التقاعد', en: 'Retired' },
  'version.successor': { ar: 'النسخة اللاحقة', en: 'Successor version' },
  'version.publish': { ar: 'انشر النسخة', en: 'Publish version' },
  'version.retire': { ar: 'أقعد النسخة', en: 'Retire version' },
  'version.publishing': { ar: 'جارٍ النشر…', en: 'Publishing…' },
  'version.retiring': { ar: 'جارٍ التقاعد…', en: 'Retiring…' },
  'version.published': {
    ar: 'نُشرت النسخة. أُعيد قراءة الدليل، و exportable في الدليل هو ما يقرره الخادم.',
    en: 'The version is published. The catalogue has been re-read; exportable in it is the server decision, not this screen.',
  },
  'version.retired': {
    ar: 'أُقعدت النسخة.',
    en: 'The version is retired.',
  },
  'version.noColumns': { ar: 'اختر عموداً واحداً على الأقل.', en: 'Choose at least one column.' },
  'version.measures': { ar: 'المقاييس', en: 'Measures' },
  'version.measuresHint': {
    ar: 'كل نسخة تحتاج مقياساً واحداً على الأقل؛ الخادم يرفض نسخة بلا مقياس، والعقد لا يعلن ذلك.',
    en: 'Every version needs at least one measure. The server refuses one without, and the contract does not say so.',
  },
  'version.noMeasures': {
    ar: 'اختر مقياساً واحداً على الأقل. الخادم يرفض نسخة بلا مقياس.',
    en: 'Choose at least one measure. The server refuses a version without one.',
  },
  'version.successorRequired': {
    ar: 'التقاعد يجب أن يسمّي النسخة التي حلّت محل هذه.',
    en: 'A retirement has to name the version that replaced this one.',
  },
  'version.successorHint': {
    ar: 'النسخة التي تحل محل هذه. اتركها فارغة إن لم تكن النسخة المنشورة.',
    en: 'The version that replaces this one. Leave it empty when this is not the published version.',
  },

  // -- templates ------------------------------------------------------------
  'template.title': { ar: 'قالب مستند', en: 'A document template' },
  'template.submit': { ar: 'أنشئ القالب', en: 'Create template' },
  'template.submitting': { ar: 'جارٍ الإنشاء…', en: 'Creating…' },
  'template.created': {
    ar: 'أُنشئ القالب. ارفع له نسخة بملف DOCX ثم انشرها.',
    en: 'The template was created. Upload a version as a DOCX file, then publish it.',
  },
  'template.list': { ar: 'القوالب التي يمكنك تأليفها', en: 'Templates you may author' },
  'template.listEmpty': {
    ar: 'لا يوجد قالب بعد. أنشئ واحداً بالأعلى لتبدأ.',
    en: 'No template yet. Create one above to begin.',
  },
  'template.docx': { ar: 'ملف DOCX', en: 'DOCX file' },
  'template.docxHint': {
    ar: 'يُرسل الملف مُرمَّزاً base64 داخل JSON، وليس رفعاً متعدد الأجزاء. الخادم يفرض حداً للحجم، وهذا العرض يرفض ما يتجاوزه قبل الإرسال.',
    en: 'The file travels base64-encoded inside JSON, not as a multipart upload. The server bounds the size; this screen refuses anything larger before sending it.',
  },
  'template.noFile': { ar: 'اختر ملفاً أولاً.', en: 'Choose a file first.' },
  'template.tooLarge': {
    ar: 'الملف أكبر من الحدّ المسموح في هذا العرض.',
    en: 'That file is larger than this screen will upload.',
  },
  'template.upload': { ar: 'ارفع النسخة', en: 'Upload version' },
  'template.uploading': { ar: 'جارٍ الرفع…', en: 'Uploading…' },
  'template.uploaded': {
    ar: 'رُفعت النسخة كمسودة. لا يمكن تثبيتها في تصدير حتى تُنشر.',
    en: 'The version was uploaded as a draft. It cannot be pinned by an export until it is published.',
  },
  'template.placeholders': { ar: 'العناصر النائبة', en: 'Placeholders' },

  // -- lifecycle glosses ----------------------------------------------------
  /**
   * A gloss for a lifecycle state the server named.
   *
   * The state itself is the server's word and is always shown verbatim beside the gloss —
   * this translates the meaning, it does not replace the fact. An unrecognised state falls
   * back to the raw word alone, so a state this build has never heard of is still legible.
   */
  'state.draft': {
    ar: 'مسودة: موجودة على الخادم، ولا يمكن تثبيتها في تصدير.',
    en: 'Draft: it exists on the server and cannot be pinned by an export.',
  },
  'state.published': {
    ar: 'منشورة: هذه هي النسخة التي يثبّت عليها التصدير.',
    en: 'Published: this is the version an export pins.',
  },
  'state.retired': {
    ar: 'مقاعدة: لم تعد تُستخدم، وحلّت محلها نسخة أخرى.',
    en: 'Retired: no longer used, and superseded by another version.',
  },

  // -- empty and loading ----------------------------------------------------
  'needsProject': {
    ar: 'اختر مشروعاً أولاً. تأليف التقارير يُقرأ لمشروع بعينه.',
    en: 'Choose a project first. Report authoring is read for one project.',
  },
  'summary.empty': {
    ar: 'لم يردّ الخادم على ملخص التأليف لهذا المشروع.',
    en: 'The server did not answer with an authoring summary for this project.',
  },
} as const;

export type AuthoringNamespace = typeof authoringStrings;