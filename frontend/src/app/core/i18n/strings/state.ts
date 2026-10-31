/**
 * The shared state namespace used by every screen: the loading, empty, error and
 * stale messages the design system requires to be designed rather than invented
 * per page.
 *
 * Registered under the namespace name `state`, so the key `state.loading`
 * resolves. Arabic is written first.
 */
import type { Namespace } from '../i18n.types';

export const stateStrings: Namespace = {
  'mainContent': { ar: 'تخطَّ إلى المحتوى', en: 'Skip to main content' },
  'loading': { ar: 'جارٍ التحميل', en: 'Loading' },
  'loadingHint': {
    ar: 'يتم الآن قراءة البيانات من الخادم.',
    en: 'Reading the data from the server.',
  },
  'empty': { ar: 'لا توجد بيانات', en: 'Nothing here yet' },
  'error': { ar: 'تعذّر تحميل هذه الصفحة.', en: 'This page could not be loaded.' },
  'errorHint': {
    ar: 'تحقق من الاتصال ثم أعد المحاولة. إن استمر الخطأ، اذكر رقم الطلب.',
    en: 'Check your connection and try again. If it continues, quote the request id.',
  },
  'retry': { ar: 'إعادة المحاولة', en: 'Try again' },
  'requestId': { ar: 'رقم الطلب', en: 'Request id' },
  'notFound.title': { ar: 'الصفحة غير موجودة', en: 'Page not found' },
  'notFound.body': {
    ar: 'الرابط الذي فتحته لا يقابل أي صفحة. عد إلى اللوحة.',
    en: 'The link you opened does not match any page. Go back to the dashboard.',
  },
  'denied.title': { ar: 'لا تملك صلاحية لهذا', en: 'You do not have access to this' },
  'denied.body': {
    ar: 'قرّر الخادم أنك لا تملك الصلاحية اللازمة. هذا ليس خطأ في التطبيق.',
    en: 'The server decided you do not have the permission for this. It is not an application error.',
  },
  'denied.requestId': {
    ar: 'سُجّل هذا القرار في سجل الأمان برقم الارتباط {{id}}.',
    en: 'This decision was recorded on the security stream with correlation id {{id}}.',
  },
  'backToDashboard': { ar: 'العودة إلى اللوحة', en: 'Back to dashboard' },
  'noPermission': {
    ar: 'هذه المعلومة تحتاج صلاحية لا تملكها. يمكنك طلبها من المسؤول.',
    en: 'This information needs a permission you do not hold. You can ask an admin for it.',
  },
  'count': { ar: '{{count}} عنصر', en: '{{count}} item(s)' },
  'updated': { ar: 'آخر تحديث {{at}}', en: 'Last updated {{at}}' },

  /**
   * The explicit "the server has no route for this" state.
   *
   * `missingLabel` precedes a route identifier, so it is its own key rather than
   * being baked into a sentence — an identifier inside prose is a translatable
   * string that will be translatable *wrongly*.
   */
  'notAvailable.title': { ar: 'غير متاح بعد', en: 'Not available yet' },
  'notAvailable.missingLabel': { ar: 'المسار المفقود:', en: 'Missing route:' },
  'notAvailable.body': {
    ar: 'العقد الموقّع لا يعرّف مساراً لهذه الشاشة، فلا توجد بيانات حقيقية لعرضها. لن نعرض أرقاماً بديلة.',
    en: 'The published contract defines no route for this screen, so there is no real data to show. No substitute figures are displayed.',
  },
  'notAvailable.noPermission': {
    ar: 'صلاحياتك لا تشمل هذه الشاشة. الخادم هو الذي يقرر، وهذا هو السلوك الصحيح.',
    en: 'Your permissions do not cover this screen. The server decides, and that is correct.',
  },
} as const;
