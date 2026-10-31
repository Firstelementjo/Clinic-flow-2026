import type { Namespace } from '../../core/i18n/i18n.types';

/**
 * The sign-in screen's copy.
 *
 * Migrated from the `copy` object that lived beside the frozen `LoginPage`, with
 * the Arabic written first. The wording is preserved, including the deliberate
 * vagueness of the credentials error: the backend answers `INVALID_CREDENTIALS`
 * for an unknown email, an inactive account and a wrong password alike, because
 * a message that distinguishes them is a user-enumeration oracle. The client
 * copy has to match that, or it undoes the server's care.
 */
export const loginStrings: Namespace = {
  title: { ar: 'مرحباً بكم في كلينك فلو', en: 'Welcome to Clinic Flow' },
  subtitle: {
    ar: 'سجّل الدخول لإدارة المواعيد وسجلات المرضى.',
    en: 'Sign in to manage appointments and patient records.',
  },
  email: { ar: 'البريد الإلكتروني', en: 'Email address' },
  emailPlaceholder: { ar: 'name@clinic.com', en: 'name@clinic.com' },
  password: { ar: 'كلمة المرور', en: 'Password' },
  passwordPlaceholder: { ar: 'أدخل كلمة المرور', en: 'Enter your password' },
  forgot: { ar: 'نسيت كلمة المرور؟', en: 'Forgot password?' },
  'forgot.title': { ar: 'استعادة كلمة المرور', en: 'Reset Password' },
  'forgot.step1Desc': {
    ar: 'أدخل بريدك الإلكتروني المسجل وسنقوم بإرسال رمز التحقق لإعادة تعيين كلمة المرور.',
    en: 'Enter your registered email address to receive a verification code and reset your password.',
  },
  'forgot.sendCode': { ar: 'إرسال رمز التحقق', en: 'Send Verification Code' },
  'forgot.step2Desc': {
    ar: 'تم إرسال رمز التحقق إلى بريدك. أدخل الرمز أدناه وعيّن كلمة المرور الجديدة (8 - 32 حرفاً).',
    en: 'A verification code was sent to your email. Enter the code below and set your new password (8-32 characters).',
  },
  'forgot.codeLabel': { ar: 'رمز التحقق (OTP)', en: 'Verification Code (OTP)' },
  'forgot.newPassword': { ar: 'كلمة المرور الجديدة', en: 'New Password' },
  'forgot.confirmPassword': { ar: 'تأكيد كلمة المرور', en: 'Confirm Password' },
  'forgot.passwordLengthHint': {
    ar: 'يجب أن تتراوح كلمة المرور بين 8 إلى 32 حرفاً.',
    en: 'Password must be between 8 to 32 characters.',
  },
  'forgot.updateSubmit': { ar: 'تحديث كلمة المرور', en: 'Update Password' },
  'forgot.success': {
    ar: 'تم تحديث كلمة المرور بنجاح! يمكنك الآن تسجيل الدخول بها.',
    en: 'Password updated successfully! You can now sign in with your new password.',
  },
  'forgot.close': { ar: 'إغلاق', en: 'Close' },
  'forgot.cancel': { ar: 'إلغاء', en: 'Cancel' },
  // `forgot` was removed with the link it labelled. There is no `/forgot-password`
  // route and no reset endpoint in the contract, so the label described a capability
  // the product does not have; a dictionary entry is not a plan for one. See Hook 2.
  showPassword: { ar: 'إظهار كلمة المرور', en: 'Show password' },
  hidePassword: { ar: 'إخفاء كلمة المرور', en: 'Hide password' },
  submit: { ar: 'تسجيل الدخول', en: 'Sign in' },
  submitting: { ar: 'جارٍ تسجيل الدخول', en: 'Signing in' },
  panelTitle: { ar: 'رعاية منظّمة.', en: 'Care, organized.' },
  panelBody: {
    ar: 'مكان واضح لفريقك لتنسيق المواعيد ورعاية المرضى.',
    en: 'A focused place for your team to coordinate schedules and patient care.',
  },
  panelNote: { ar: 'لموظفي العيادة المصرّح لهم', en: 'For authorized clinic staff' },
  'error.emailRequired': { ar: 'أدخل بريدك الإلكتروني.', en: 'Enter your email address.' },
  'error.emailInvalid': {
    ar: 'أدخل بريداً إلكترونياً صالحاً، مثل name@clinic.com.',
    en: 'Enter a valid email address, such as name@clinic.com.',
  },
  'error.passwordRequired': { ar: 'أدخل كلمة المرور.', en: 'Enter your password.' },
  'error.invalidCredentials': {
    ar: 'البريد الإلكتروني أو كلمة المرور غير مطابقين لسجلاتنا. تحقق من الحقلين وحاول مرة أخرى.',
    en: 'The email or password does not match our records. Check both fields and try again.',
  },
  'error.rateLimited': {
    ar: 'محاولات كثيرة في وقت قصير. انتظر قليلاً ثم حاول مرة أخرى.',
    en: 'Too many attempts in a short time. Wait a little, then try again.',
  },
  'error.unreachable': {
    ar: 'تعذّر الاتصال ببوابة العيادة. تحقق من اتصالك وحاول مرة أخرى.',
    en: 'Could not connect to the clinic portal. Check your connection and try again.',
  },
  'error.unknown': {
    ar: 'تعذّر تسجيل الدخول. حاول مرة أخرى بعد قليل.',
    en: 'Could not sign you in. Try again shortly.',
  },
} as const;
