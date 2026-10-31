import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * People, and the 360 view behind one person.
 *
 * Arabic first. The wording that matters here is the *separation* wording: a
 * customer-facing history and an internal note are different things, and
 * `PROJECT_REQUIREMENTS` FR-3.4 requires they stay separate. So every internal
 * surface says "internal" and no timeline merges the two.
 */
export const peopleStrings: Namespace = {
  title: { ar: 'الأشخاص', en: 'People' },
  description: {
    ar: 'سجلّات العملاء في النطاق الذي تراه، وتفاصيل كل واحد منهم.',
    en: 'Customer records in the scope you can see, and the detail behind each one.',
  },
  refresh: { ar: 'تحديث', en: 'Refresh' },

  // -- list -----------------------------------------------------------------
  'list.search': { ar: 'ابحث بالاسم أو البريد أو الهاتف', en: 'Search by name, email or phone' },
  'list.searchHint': {
    ar: 'يُرسل البحث إلى الخادم، فلا يقتصر على ما هو محمّل.',
    en: 'The search is sent to the server, so it is not limited to what is loaded.',
  },
  'list.empty': { ar: 'لا يوجد عملاء', en: 'No customers' },
  'list.emptyBody': {
    ar: 'لم يُسجَّل أي عميل في هذا النطاق بعد.',
    en: 'No customer has been recorded in this scope yet.',
  },
  'list.noResults': { ar: 'لا نتائج مطابقة', en: 'No matching customers' },
  'list.count': { ar: '{{count}} عميل', en: '{{count}} customer(s)' },
  'list.loadMore': { ar: 'تحميل المزيد', en: 'Load more' },
  'list.conversations': { ar: 'محادثات', en: 'Conversations' },
  'list.lastSeen': { ar: 'آخر ظهور', en: 'Last seen' },
  'list.latestTicket': { ar: 'آخر بلاغ', en: 'Latest ticket' },
  'list.tag': { ar: 'وسم', en: 'Tag' },

  // -- detail ---------------------------------------------------------------
  'detail.title': { ar: 'ملف العميل', en: 'Customer record' },
  'detail.identity': { ar: 'الهوية', en: 'Identity' },
  'detail.identities': { ar: 'الهويات المرتبطة', en: 'Linked identities' },
  'detail.platform': { ar: 'المنصة', en: 'Platform' },
  'detail.externalId': { ar: 'المعرّف الخارجي', en: 'External id' },
  'detail.verified': { ar: 'موثّقة', en: 'Verified' },
  'detail.unverified': { ar: 'غير موثّقة', en: 'Not verified' },
  'detail.conversations': { ar: 'المحادثات', en: 'Conversations' },
  'detail.conversationsEmpty': {
    ar: 'لا توجد محادثات لهذا العميل.',
    en: 'This customer has no conversations.',
  },
  'detail.leads': { ar: 'المتابعات', en: 'Follow-ups' },
  'detail.appointments': { ar: 'المواعيد', en: 'Appointments' },
  'detail.notes': { ar: 'ملاحظات داخلية', en: 'Internal notes' },
  'detail.notesEmpty': {
    ar: 'لا توجد ملاحظات داخلية. هذه ليست سجلّ العميل نفسه.',
    en: 'No internal notes. This is not the customer-facing history.',
  },
  'detail.addNote': { ar: 'أضف ملاحظة داخلية', en: 'Add an internal note' },
  'detail.noteBody': { ar: 'نص الملاحظة', en: 'Note text' },
  'detail.noteSave': { ar: 'حفظ الملاحظة', en: 'Save note' },
  'detail.noteSaving': { ar: 'جارٍ الحفظ…', en: 'Saving…' },
  'detail.noteSaved': { ar: 'حُفظت الملاحظة.', en: 'The note was saved.' },
  'detail.noteEmpty': { ar: 'اكتب شيئاً قبل الحفظ.', en: 'Write something before saving.' },
  'detail.noteAuthor': { ar: 'كتبها', en: 'Written by' },
  'detail.noteAt': { ar: 'في', en: 'At' },
  'detail.noteDelete': { ar: 'حذف الملاحظة', en: 'Delete note' },
  'detail.noteDeleted': { ar: 'حُذفت الملاحظة.', en: 'The note was deleted.' },
  'detail.edit': { ar: 'تعديل', en: 'Edit' },
  'detail.saving': { ar: 'جارٍ الحفظ…', en: 'Saving…' },
  'detail.saved': { ar: 'حُفظت التغييرات.', en: 'The changes were saved.' },
  'detail.conflict': {
    ar: 'عُدّل الملف بين فتحك هذه الشاشة وحفظك. اقرأ النسخة الحالية ثم أعد الحفظ.',
    en: 'The record changed between you opening this screen and saving. Read the current version, then save again.',
  },
  'detail.displayName': { ar: 'الاسم المعروض', en: 'Display name' },
  'detail.primaryEmail': { ar: 'البريد الإلكتروني', en: 'Email' },
  'detail.primaryPhone': { ar: 'الهاتف', en: 'Phone' },
  'detail.locale': { ar: 'اللغة', en: 'Language' },
  'detail.tags': { ar: 'الوسوم', en: 'Tags' },
  'detail.tagsHint': {
    ar: 'افصل بينها بفاصلة.',
    en: 'Separate with commas.',
  },
  'detail.metadata': { ar: 'بيانات إضافية', en: 'Additional data' },
  'detail.save': { ar: 'حفظ', en: 'Save' },
  'detail.cancel': { ar: 'إلغاء', en: 'Cancel' },
  'detail.back': { ar: 'العودة إلى القائمة', en: 'Back to the list' },
  'detail.cxId': { ar: 'معرّفCX', en: 'CX id' },
  'detail.project': { ar: 'المشروع', en: 'Project' },
  'detail.firstSeen': { ar: 'أول ظهور', en: 'First seen' },
  'detail.lastSeen': { ar: 'آخر ظهور', en: 'Last seen' },
  'detail.selectPrompt': { ar: 'اختر عميلاً', en: 'Choose a customer' },
  'detail.selectPromptBody': {
    ar: 'اختر عميلاً من القائمة لعرض ملفه الكامل هنا.',
    en: 'Choose a customer from the list to see the full record here.',
  },

  // -- separation -----------------------------------------------------------
  'separation.note': {
    ar: 'هذه ملاحظات داخلية لا يراها العميل، وليست سجلّه الذي يراه.',
    en: 'These are internal notes the customer does not see, and are not the record they see.',
  },

  // -- vocabulary -----------------------------------------------------------
  'followUp.status.OPEN': { ar: 'مفتوحة', en: 'Open' },
  'followUp.status.DONE': { ar: 'منجزة', en: 'Done' },
  'appointment.status.CONFIRMED': { ar: 'مؤكّد', en: 'Confirmed' },
  'appointment.status.CANCELLED': { ar: 'ملغى', en: 'Cancelled' },
  'appointment.status.COMPLETED': { ar: 'مكتمل', en: 'Completed' },
  'appointment.duration': { ar: '{{minutes}} دقيقة', en: '{{minutes}} min' },
} as const;
