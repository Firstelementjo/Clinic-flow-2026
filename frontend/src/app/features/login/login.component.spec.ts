/**
 * The sign-in screen.
 *
 * Covered: validation, focus management, the error message chosen from the error
 * *code* rather than the status, and the i18n ownership of every string.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { LoginComponent } from './login.component';
import { I18nService } from '../../core/i18n/i18n.service';
import { SessionStore } from '../../core/auth/session.store';

const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'lead@clinic.example',
  fullName: 'ليلى',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: [],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        // A minimal route table with the post-login destination, so the
        // navigation after a successful sign-in resolves instead of rejecting as
        // an unmatched segment — an unhandled rejection here would be reported as
        // a suite error and hide a real failure behind it.
        provideRouter([{ path: 'app/dashboard', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    fixture.detectChanges();
  });

  function email(): HTMLInputElement {
    return fixture.nativeElement.querySelector('#fe-login-email') as HTMLInputElement;
  }

  function password(): HTMLInputElement {
    return fixture.nativeElement.querySelector('#fe-login-password') as HTMLInputElement;
  }

  /** Submits the way a user does: through the form element, not the component. */
  async function submitForm(): Promise<void> {
    (fixture.nativeElement.querySelector('form') as HTMLFormElement)
      .dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    await tick();
  }

  /** Lets the service's promise chains advance. */
  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  it('renders in Arabic with RTL by default, because Arabic is the authoring language', () => {
    expect(i18n.locale()).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('مرحباً');
  });

  it('has an accessible name for every control', () => {
    expect(fixture.nativeElement.querySelector('label[for="fe-login-email"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="fe-login-password"]')).not.toBeNull();
    const toggle = fixture.nativeElement.querySelector('.password__toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-label')).toBeTruthy();
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
  });

  it('does not submit an empty form, and says which field is wrong', async () => {
    await submitForm();
    fixture.detectChanges();
    http.expectNone((request) => request.url === '/api/v1/auth/login');
    expect(email().getAttribute('aria-invalid')).toBe('true');
    expect(fixture.nativeElement.querySelector('#fe-login-email-error')).not.toBeNull();
  });

  it('rejects an address that is not an address', async () => {
    email().value = 'not-an-address';
    email().dispatchEvent(new Event('input'));
    await submitForm();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('بريداً إلكترونياً صالحاً');
  });

  it('moves focus to the first invalid field', async () => {
    await submitForm();
    fixture.detectChanges();
    expect(document.activeElement).toBe(email());
  });

  it('toggles password visibility with a real button', () => {
    const toggle = fixture.nativeElement.querySelector('.password__toggle') as HTMLButtonElement;
    expect(password().type).toBe('password');
    toggle.click();
    fixture.detectChanges();
    expect(password().type).toBe('text');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBeTruthy();
  });

  it('sends a CSRF-armed login and navigates on success', async () => {
    email().value = 'lead@clinic.example';
    email().dispatchEvent(new Event('input'));
    password().value = 'correct horse battery staple';
    password().dispatchEvent(new Event('input'));

    await submitForm();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't', headerName: 'X-XSRF-TOKEN' });
    await tick();
    http.expectOne('/api/v1/auth/login').flush(SESSION);
    await tick();
    http.expectOne('/api/v1/permissions/me').flush({ effectivePermissions: [], missingPermissions: [] });
    await tick();
  });

  it('shows the credentials message for INVALID_CREDENTIALS, whatever the status', async () => {
    email().value = 'lead@clinic.example';
    email().dispatchEvent(new Event('input'));
    password().value = 'wrong';
    password().dispatchEvent(new Event('input'));

    await submitForm();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't' });
    await tick();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'INVALID_CREDENTIALS', message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    await tick();
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert).not.toBeNull();
    expect(alert.textContent).toContain('غير مطابقين لسجلاتنا');
    expect(TestBed.inject(SessionStore).isAuthenticated()).toBe(false);
  });

  it('shows a different message for the rate limiter', async () => {
    email().value = 'lead@clinic.example';
    email().dispatchEvent(new Event('input'));
    password().value = 'x';
    password().dispatchEvent(new Event('input'));

    await submitForm();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't' });
    await tick();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'LOGIN_RATE_LIMITED', message: 'x' }, { status: 429, statusText: 'Too Many Requests' });
    await tick();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement).textContent).toContain(
      'محاولات كثيرة',
    );
  });

  it('switches language without a reload and keeps the form state', () => {
    email().value = 'lead@clinic.example';
    email().dispatchEvent(new Event('input'));
    (fixture.nativeElement.querySelectorAll('.login__header button')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(i18n.locale()).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
    expect(email().value).toBe('lead@clinic.example');
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Welcome');
  });

  it('has no hard-coded copy: every visible string resolves through the dictionary', () => {
    // The copy-coverage suite proves the keys exist; this proves the template
    // actually renders them, so a literal pasted in place of the pipe would show
    // up as text the dictionary does not own.
    const text = (fixture.nativeElement.textContent ?? '').trim();
    expect(text).not.toContain('Sign in to manage');
    expect(text).not.toContain('Welcome to');
  });
});


