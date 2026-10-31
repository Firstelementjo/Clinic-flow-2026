import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * The dashboard's copy.
 *
 * The dashboard is a **replica** of the ToothLogic All-in-One dashboard: the
 * layout, the cards and the chart are reproduced exactly, and so is the
 * discipline the rest of the product keeps — every figure on it comes from a
 * route, and a figure that could not be read is drawn as an absence.
 *
 * That discipline is also why this namespace exists. The frozen page had no
 * dictionary at all: every label was an English literal in the template, and
 * its Arabic was absent, so an Arabic reader who opened the landing screen
 * saw an English screen. The replica keeps the pixels and takes the copy from
 * here instead. Arabic is written first.
 *
 * Two rules the copy follows:
 *
 * - **Sentence case**, because these are labels, not headings in a document.
 * - **No key whose two languages are the same string**, because a shared value
 *   means one of the two was never written. The i18n coverage test enforces it.
 */
export const dashboardStrings: Namespace = {
  title: { ar: 'لوحة العيادة', en: 'Clinic dashboard' },
  breadcrumb: { ar: 'اللوحة الرئيسية', en: 'Main dashboard' },

  // The greeting follows the reader's own clock.
  'greeting.morning': { ar: 'صباح الخير', en: 'Good morning' },
  'greeting.afternoon': { ar: 'طاب يومك', en: 'Good afternoon' },
  'greeting.evening': { ar: 'مساء الخير', en: 'Good evening' },

  'performance.title': { ar: 'الأداء', en: 'Performance' },
  'performance.date': { ar: 'اختر التاريخ', en: 'Select date' },
  'performance.submit': { ar: 'تطبيق', en: 'Submit' },
  'performance.loading': { ar: 'جارٍ تحميل الأداء…', en: 'Loading performance…' },
  'performance.window': {
    ar: 'الأداء المعروض لتاريخ {{date}}',
    en: 'Showing performance for {{date}}',
  },
  'performance.denied': {
    ar: 'لا تملك صلاحية قراءة تقرير أداء العيادة.',
    en: 'You do not have permission to read the clinic performance report.',
  },
  'performance.failed': {
    ar: 'تعذّر تحميل تقرير أداء العيادة.',
    en: 'The clinic performance report could not be loaded.',
  },

  /**
   * Shown when a card's source is behind a permission the caller does not
   * hold. It says the server decided, because a blank card otherwise reads as
   * a broken screen rather than as a deliberate withholding.
   */
  withheld: {
    ar: 'بعض الأرقام لا تظهر لأن صلاحياتك لا تشملها.',
    en: 'Some figures are not shown because your permissions do not cover them.',
  },

  'card.clinics': { ar: 'إجمالي عدد العيادات', en: 'Total number of clinics' },
  'card.services': { ar: 'إجمالي الخدمات النشطة', en: 'Total active services' },
  'card.appointments': { ar: 'إجمالي المواعيد', en: 'Total appointments' },
  'card.doctors': { ar: 'إجمالي عدد الأطباء', en: 'Total number of doctors' },
  'card.patients': { ar: 'إجمالي عدد المرضى', en: 'Total number of patients' },
  'card.revenue': { ar: 'إجمالي الإيرادات', en: 'Total revenue' },

  'chart.title': { ar: 'إجمالي الإيرادات', en: 'Total revenue' },
  'chart.loading': { ar: 'جارٍ تحميل الإيرادات…', en: 'Loading revenue…' },
  'chart.empty': {
    ar: 'لم يُسجَّل أي إيراد في هذه الفترة.',
    en: 'No revenue was recorded in this period.',
  },

  'period.year': { ar: 'السنة', en: 'Year' },
  'period.quarter': { ar: 'الربع', en: 'Quarter' },
  'period.month': { ar: 'الشهر', en: 'Month' },
  'period.week': { ar: 'الأسبوع', en: 'Week' },

  'age.title': { ar: 'المرضى حسب العمر', en: 'Visited patients by age' },
  'age.band1': { ar: 'من 0 إلى 25 سنة', en: '0-25 years' },
  'age.band2': { ar: 'من 26 إلى 50 سنة', en: '26-50 years' },
  'age.band3': { ar: 'أكثر من 50 سنة', en: 'Over 50 years' },

  'upcoming.title': { ar: 'المواعيد القادمة', en: 'Upcoming appointments' },
  'upcoming.empty': {
    ar: 'لا توجد مواعيد خلال الأيام السبعة القادمة.',
    en: 'There are no appointments in the next seven days.',
  },
  'upcoming.patient': { ar: 'مريض', en: 'Patient' },
  'upcoming.doctor': { ar: 'طبيب', en: 'Doctor' },
  'upcoming.service': { ar: 'استشارة', en: 'Consultation' },

  footer: { ar: 'كلينك فلو', en: 'Clinic Flow' },
} as const;
