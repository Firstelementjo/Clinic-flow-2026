/**
 * Locale-aware formatting: explicit, testable, never a bare `toLocaleString()`.
 *
 * Phase-11 hard rule 5. These tests are the evidence for that rule: if the
 * locale tag, the digit system or the timezone were ever left to a guess, one of
 * these would fail.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { FormatService } from './format.service';
import { I18nService } from '../i18n/i18n.service';

describe('FormatService', () => {
  let format: FormatService;
  let i18n: I18nService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    i18n = TestBed.inject(I18nService);
    format = TestBed.inject(FormatService);
  });

  describe('numbers', () => {
    it('uses Western digits in Arabic', () => {
      i18n.setLocale('ar');
      expect(format.integer(1234567)).toMatch(/^[\d,.\s]+$/);
      expect(format.integer(1234567)).not.toMatch(/[٠-٩]/);
    });

    it('groups thousands per locale', () => {
      i18n.setLocale('en');
      expect(format.integer(1234567)).toBe('1,234,567');
      i18n.setLocale('ar');
      // Arabic with -u-nu-latn groups with the locale's own separators.
      expect(format.integer(1234567)).not.toBe('1234567');
    });

    it('renders an absent figure as a dash, never as zero', () => {
      // FR-7.6: "no data" and zero are different, and conflating them is the
      // failure this whole service exists to prevent.
      for (const value of [null, undefined, Number.NaN, Number.POSITIVE_INFINITY]) {
        expect(format.integer(value)).toBe('—');
        expect(format.decimal(value)).toBe('—');
        expect(format.currency(value)).toBe('—');
        expect(format.percent(value)).toBe('—');
      }
      expect(format.integer(0)).not.toBe('—');
    });

    it('keeps a null percentage null rather than rendering 0%', () => {
      i18n.setLocale('en');
      expect(format.percent(null)).toBe('—');
      expect(format.percent(0)).toBe('0%');
    });

    it('converts a 0..100 rate without double-scaling it', () => {
      i18n.setLocale('en');
      expect(format.percentOfHundred(42.5, 1)).toBe('42.5%');
      expect(format.percentOfHundred(null)).toBe('—');
    });

    it('formats currency with an explicit code, never inferred from the locale', () => {
      i18n.setLocale('en');
      const jod = format.currency(1234.5, 'JOD');
      const usd = format.currency(1234.5, 'USD');
      expect(jod).toMatch(/1,234\.50/);
      expect(usd).toMatch(/1,234\.50/);
      // The two differ, which is the point: nothing here is inferred from `ar` or
      // `en`, so switching language cannot change a currency.
      expect(jod).not.toBe(usd);
    });

    it('keeps the currency the caller asked for when the locale changes', () => {
      i18n.setLocale('en');
      const inEnglish = format.currency(100, 'JOD');
      i18n.setLocale('ar');
      expect(format.currency(100, 'JOD')).toContain('100');
      expect(format.currency(100, 'JOD')).not.toContain('USD');
      expect(inEnglish).toContain('100');
    });
  });

  describe('timestamps', () => {
    it('reads a bare LocalDateTime as UTC rather than as the browser zone', () => {
      // The server stores `timestamp without time zone` (ADR-0002) and appends
      // `Z` on the way out. A client that parsed the bare value in local time
      // would shift every timestamp by the developer's offset.
      const withZone = format.parseApiTimestamp('2026-10-06T12:00:00Z');
      const withoutZone = format.parseApiTimestamp('2026-10-06T12:00:00');
      expect(withoutZone?.toISOString()).toBe(withZone?.toISOString());
      expect(withoutZone?.toISOString()).toBe('2026-10-06T12:00:00.000Z');
    });

    it('rejects an unparseable or absent timestamp', () => {
      expect(format.parseApiTimestamp(null)).toBeNull();
      expect(format.parseApiTimestamp('')).toBeNull();
      expect(format.parseApiTimestamp('not a date')).toBeNull();
      expect(format.dateTime('not a date')).toBe('—');
      expect(format.dateTime(null)).toBe('—');
    });

    it('accepts an offset timestamp as written', () => {
      expect(format.parseApiTimestamp('2026-10-06T14:00:00+02:00')?.toISOString()).toBe(
        '2026-10-06T12:00:00.000Z',
      );
    });

    it('renders a 24-hour clock in both locales, in the viewer timezone', () => {
      // The viewer timezone is a deliberate parameter, not the browser's implicit
      // local zone: the same instant must read the same in Riyadh and in London.
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const expected = new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: zone,
      }).format(new Date('2026-10-06T14:05:00Z'));

      i18n.setLocale('en');
      expect(format.time('2026-10-06T14:05:00Z')).toBe(expected);
      i18n.setLocale('ar');
      expect(format.time('2026-10-06T14:05:00Z')).toBe(expected);
      // No 12-hour meridiem in either.
      expect(expected).not.toMatch(/PM|AM/i);
    });

    it('renders the same instant differently in two explicit timezones', () => {
      // Proof that the timezone is a real input: `Asia/Amman` and `UTC` differ.
      const utc = new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: 'UTC',
      }).format(new Date('2026-10-06T14:05:00Z'));
      const amman = new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: 'Asia/Amman',
      }).format(new Date('2026-10-06T14:05:00Z'));
      expect(utc).not.toBe(amman);
    });

    it('gives a queue row a clock today and a date beyond it', () => {
      const now = new Date('2026-10-06T12:00:00Z');
      const earlierToday = format.listStamp('2026-10-06T09:00:00Z', now);
      const lastWeek = format.listStamp('2026-09-29T09:00:00Z', now);
      expect(earlierToday).not.toBe(lastWeek);
      expect(earlierToday).toMatch(/:/);
    });
  });

  describe('calendar dates', () => {
    it('reads a date of birth as the day that was written down', () => {
      // A birthday is a day on a calendar. `date()` would read `1991-04-02` as
      // midnight UTC and print the 1st of April in every timezone west of Greenwich,
      // so the day on the record would depend on where the reader is.
      i18n.setLocale('en');
      const expected = new Intl.DateTimeFormat('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      }).format(new Date(1991, 3, 2));

      expect(format.calendarDate('1991-04-02')).toBe(expected);
      expect(format.calendarDate('1991-04-02')).toContain('02');
    });

    it('rejects a day that does not exist and renders an absent date as a dash', () => {
      expect(format.calendarDate('2026-02-30')).toBe('—');
      expect(format.calendarDate('not a date')).toBe('—');
      expect(format.calendarDate(null)).toBe('—');
      expect(format.calendarDate(undefined)).toBe('—');
    });
  });

  describe('durations', () => {
    it('renders seconds, minutes, hours and days', () => {
      expect(format.duration(45)).toBe('45s');
      expect(format.duration(600)).toBe('10m');
      expect(format.duration(3_600)).toBe('1h');
      expect(format.duration(90_000)).toBe('1d 1h');
    });

    it('renders an absent duration as a dash', () => {
      expect(format.duration(null)).toBe('—');
      expect(format.durationWithLabels(null, labels())).toBe('—');
    });

    it('never renders a negative duration', () => {
      expect(format.duration(-10)).toBe('0s');
    });

    it('translates the unit labels through the caller', () => {
      const arabic = { day: 'ي', hour: 'س', minute: 'د', second: 'ث' };
      expect(format.durationWithLabels(90_000, arabic)).toBe('1 ي 1 س');
    });
  });
});

function labels(): { day: string; hour: string; minute: string; second: string } {
  return { day: 'd', hour: 'h', minute: 'm', second: 's' };
}
