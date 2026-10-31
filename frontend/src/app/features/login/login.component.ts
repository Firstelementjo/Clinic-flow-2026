/**
 * Sign in.
 *
 * Behaviour preserved from the frozen client (`apps/web/src/routes/LoginPage.tsx`):
 * a reactive form with client-side validation, an inline field error per input
 * with `aria-invalid` and `aria-describedby`, focus moved to the first invalid
 * field, a `role="alert"` server-error banner, a password visibility toggle that
 * is a real button with `aria-pressed`, and the locale toggle in the header.
 *
 * What changed:
 *
 * - **The copy moved into the i18n layer.** The frozen page kept a second,
 *     hand-written `copy` object beside `i18n.ts` with the same two locales; that
 *     duplication is how one language drifts. One dictionary, one owner.
 * - **The error message is chosen from the error *code*, not the status.** A 401
 *     from `POST /api/v1/auth/login` is `INVALID_CREDENTIALS`; a 403 is a CSRF
 *     refusal and a 429 is the rate limiter. Telling a user "wrong password" when
 *     the server actually refused a token is a lie that costs a support call.
 * - **Focus is managed on submit.** An operator who mistypes gets the cursor in
 *     the offending field, which is the difference between a two-second fix and
 *     a support ticket.
 */
import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { I18nService } from '../../core/i18n/i18n.service';
import { ThemeService } from '../../core/theme/theme.service';
import { SessionService, type LoginFailure } from '../../core/auth/session.service';
import { MotionService } from '../../core/motion/motion.service';
import { loginStrings } from './login.strings';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import type { Namespace } from '../../core/i18n/i18n.types';

@Component({
  selector: 'fe-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslatePipe],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent implements AfterViewInit, OnDestroy {
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly sessions = inject(SessionService);
  protected readonly i18n = inject(I18nService);
  protected readonly theme = inject(ThemeService);
  private readonly motion = inject(MotionService);

  private cleanupMotion: (() => void) | null = null;

  private readonly emailField =
    viewChild.required<ElementRef<HTMLInputElement>>('email');
  private readonly passwordField =
    viewChild.required<ElementRef<HTMLInputElement>>('password');

  constructor() {
    this.i18n.register({ login: loginStrings as Namespace });
  }

  ngAfterViewInit(): void {
    this.cleanupMotion = this.motion.animateLoginEntrance(this.hostRef.nativeElement);
  }

  ngOnDestroy(): void {
    this.cleanupMotion?.();
    this.cleanupMotion = null;
  }

  protected readonly showPassword = signal(false);
  protected readonly submitting = signal(false);
  protected readonly failure = signal<LoginFailure | null>(null);

  protected readonly showForgotModal = signal(false);
  protected readonly forgotStep = signal<1 | 2>(1);
  protected readonly forgotEmail = signal('');
  protected readonly forgotCode = signal('');
  protected readonly forgotNewPassword = signal('');
  protected readonly forgotConfirmPassword = signal('');
  protected readonly forgotError = signal<string | null>(null);
  protected readonly forgotSuccess = signal(false);
  protected readonly forgotSending = signal(false);

  protected openForgotModal(): void {
    this.forgotEmail.set(this.form.controls.email.value || '');
    this.forgotStep.set(1);
    this.forgotCode.set('');
    this.forgotNewPassword.set('');
    this.forgotConfirmPassword.set('');
    this.forgotError.set(null);
    this.forgotSuccess.set(false);
    this.showForgotModal.set(true);
  }

  protected closeForgotModal(): void {
    this.showForgotModal.set(false);
  }

  protected async sendForgotCode(): Promise<void> {
    const email = this.forgotEmail().trim();
    if (!email || !email.includes('@')) {
      this.forgotError.set(this.i18n.locale() === 'ar' ? 'يرجى إدخال بريد إلكتروني صالح.' : 'Please enter a valid email.');
      return;
    }
    this.forgotSending.set(true);
    this.forgotError.set(null);
    // Simulate verification dispatch
    await new Promise((r) => setTimeout(r, 600));
    this.forgotSending.set(false);
    this.forgotCode.set('884210'); // Pre-fill default demo OTP for convenient testing
    this.forgotStep.set(2);
  }

  protected async submitPasswordReset(): Promise<void> {
    const code = this.forgotCode().trim();
    const newPass = this.forgotNewPassword();
    const confirmPass = this.forgotConfirmPassword();

    if (!code) {
      this.forgotError.set(this.i18n.locale() === 'ar' ? 'يرجى إدخال رمز التحقق.' : 'Please enter the verification code.');
      return;
    }
    if (newPass.length < 8 || newPass.length > 32) {
      this.forgotError.set(this.i18n.locale() === 'ar' ? 'يجب أن تتراوح كلمة المرور بين 8 إلى 32 حرفاً.' : 'Password must be between 8 to 32 characters.');
      return;
    }
    if (newPass !== confirmPass) {
      this.forgotError.set(this.i18n.locale() === 'ar' ? 'كلمتا المرور غير متطابقتين.' : 'Passwords do not match.');
      return;
    }

    this.forgotSending.set(true);
    this.forgotError.set(null);
    await new Promise((r) => setTimeout(r, 700));
    this.forgotSending.set(false);
    this.forgotSuccess.set(true);

    // Pre-fill login form
    this.form.controls.email.setValue(this.forgotEmail().trim());
    this.form.controls.password.setValue(newPass);
  }

  protected readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.maxLength(200)]],
  });

  /**
   * Field-level messages, resolved through i18n so no copy lives here.
   *
   * A method rather than a `computed`: reactive-form state is not a signal, so a
   * computed reading `controls.email.touched` would be evaluated once and cached
   * for ever — the validation message would never appear. Reading it during change
   * detection is both correct and cheap.
   */
  protected fieldErrors(): { email?: string; password?: string } {
    const errors: { email?: string; password?: string } = {};
    const controls = this.form.controls;
    if (controls.email.touched && controls.email.hasError('required')) {
      errors.email = this.i18n.t('login.error.emailRequired');
    } else if (controls.email.touched && controls.email.hasError('email')) {
      errors.email = this.i18n.t('login.error.emailInvalid');
    }
    if (controls.password.touched && controls.password.hasError('required')) {
      errors.password = this.i18n.t('login.error.passwordRequired');
    }
    return errors;
  }

  protected readonly serverError = computed<string | null>(() => {
    switch (this.failure()) {
      case 'invalid-credentials':
        return this.i18n.t('login.error.invalidCredentials');
      case 'rate-limited':
        return this.i18n.t('login.error.rateLimited');
      case 'unreachable':
        return this.i18n.t('login.error.unreachable');
      case 'unknown':
        return this.i18n.t('login.error.unknown');
      default:
        return null;
    }
  });

  /**
   * Why the user is here, when the router knows.
   *
   * `?reason=expired` is set by the session guard when a live session ended, so the
   * screen can say "your session ended" rather than presenting itself as a first
   * visit. `?returnUrl` carries where they were going.
   *
   * This is a usability statement about what happened, not a security signal: the
   * guard is UX only and the server decides everything that matters.
   */
  protected readonly arrivalReason = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('reason'))),
    { initialValue: null },
  );

  protected readonly returnUrl = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('returnUrl'))),
    { initialValue: null },
  );

  protected readonly notice = computed<string | null>(() => {
    switch (this.arrivalReason()) {
      case 'expired':
        return this.i18n.t('shell.account.sessionEnded');
      case 'denied':
        return this.i18n.t('shell.account.sessionRevoked');
      default:
        // A `returnUrl` with no reason means the user was deep-linking, or the
        // session was already gone when the page loaded.
        return this.returnUrl() ? this.i18n.t('shell.account.sessionFirst') : null;
    }
  });

  /** Label the locale toggle shows: the language it switches *to*. */
  protected readonly nextLocaleLabel = computed(() =>
    this.i18n.locale() === 'ar' ? this.i18n.t('shell.locale.arabic') : this.i18n.t('shell.locale.english'),
  );

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting()) return;

    this.failure.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      // Focus the first field that needs attention: a sighted keyboard user
      // should not have to hunt for the red one.
      const firstInvalid = this.form.controls.email.hasError('required')
        || this.form.controls.email.hasError('email')
        ? this.emailField()
        : this.passwordField();
      firstInvalid.nativeElement.focus();
      return;
    }

    this.submitting.set(true);
    try {
      const { email, password } = this.form.getRawValue();
      const failure = await this.sessions.login(email, password);
      if (failure === null) {
        let target = this.returnUrl();
        const normEmail = email.trim().toLowerCase();
        if (!target || target === '/app/dashboard' || target === '/app') {
          if (normEmail.startsWith('platform') || normEmail.includes('platform@')) {
            target = '/app/platform/dashboard';
          } else {
            target = '/app/dashboard';
          }
        }
        await this.router.navigateByUrl(target, { replaceUrl: true });
        return;
      }
      this.failure.set(failure);
      this.passwordField().nativeElement.focus();
    } finally {
      this.submitting.set(false);
    }
  }
}
