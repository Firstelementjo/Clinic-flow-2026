import { Injectable, inject } from '@angular/core';
import { I18nService } from '../i18n/i18n.service';
import type { Locale } from '../i18n/i18n.types';

/**
 * Locale-aware formatting, explicit and testable (phase-11 hard rule 5).
 *
 * Every number, date, time, duration and currency in the product goes through
 * this service. A bare `toLocaleString()` anywhere is a defect, because the two
 * things that decide whether a figure is correct — the locale tag and the
 * timezone — are both arguments here and nowhere else.
 *
 * Three decisions worth recording:
 *
 * - **Digits.** Western (0–9) in both locales. These are operational figures
 *   compared by position against a KPI card, and Gulf business software sets
 *   them that way; `ar-u-nu-latn` is what holds it.
 * - **Timezone.** Explicit and defaulting to the runtime's zone, never a
 *   per-component `Date` with an implicit local zone. The API returns
 *   `LocalDateTime`-derived strings with a `Z` suffix appended
 *   (`RealtimeController`, `ChannelCapabilityController`), which is a value the
 *   server means as wall-clock time; those are parsed as UTC and rendered in the
 *   viewer's zone, and `formatServerWallClock` is there for the surfaces that
 *   must show the server's own clock.
 * - **Currency.** `Intl.NumberFormat` with an explicit currency code. JOD is the
 *   clinic's currency and the design says revenue is always in JOD; nothing
 *   infers it from a locale.
 */
@Injectable({ providedIn: 'root' })
export class FormatService {
  private readonly i18n = inject(I18nService);

  /** The viewer's IANA timezone. `undefined` in a non-DOM test environment. */
  private readonly zone = (): string | undefined => Intl.DateTimeFormat().resolvedOptions().timeZone;

  // -- numbers -------------------------------------------------------------

  integer(value: number | null | undefined, options?: Intl.NumberFormatOptions): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return this.dash;
    return new Intl.NumberFormat(this.i18n.intlLocale(), {
      maximumFractionDigits: 0,
      ...options,
    }).format(value);
  }

  decimal(value: number | null | undefined, fractionDigits = 1): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return this.dash;
    return new Intl.NumberFormat(this.i18n.intlLocale(), {
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(value);
  }

  /** A ratio as a percentage. `null` stays null so "no data" is not "0%". */
  percent(value: number | null | undefined, fractionDigits = 0): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return this.dash;
    return new Intl.NumberFormat(this.i18n.intlLocale(), {
      style: 'percent',
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(value);
  }

  /**
   * A percentage from a value already expressed 0..100, which is how the
   * backend reports rates. Named differently from `percent` so the unit cannot
   * be guessed wrong at the call site.
   */
  percentOfHundred(value: number | null | undefined, fractionDigits = 0): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return this.dash;
    return new Intl.NumberFormat(this.i18n.intlLocale(), {
      style: 'percent',
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(value / 100);
  }

  currency(amount: number | null | undefined, currency = 'JOD', fractionDigits = 2): string {
    if (amount === null || amount === undefined || !Number.isFinite(amount)) return this.dash;
    return new Intl.NumberFormat(this.i18n.intlLocale(), {
      style: 'currency',
      currency,
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(amount);
  }

  /** What a "no value" figure looks like. Deliberately not `0`. */
  get dash(): string {
    return '—';
  }

  // -- dates and times -----------------------------------------------------

  /**
   * Parses an API timestamp.
   *
   * Two shapes arrive and they mean different things:
   *
   * - `2026-10-06T12:34:56.789Z` — a real instant.
   * - `2026-10-06T12:34:56` — a server wall clock. The Spring side stores
   *   `timestamp without time zone` (ADR-0002) and appends `Z` when it
   *   serialises, so the value is read as UTC. `parseApiTimestamp` handles
   *   both by treating a bare `LocalDateTime` as UTC rather than as the
   *   browser's local zone, which is the bug that makes every timestamp shift
   *   by the developer's offset.
   */
  parseApiTimestamp(value: string | null | undefined): Date | null {
    if (!value) return null;
    const trimmed = value.trim();
    if (trimmed === '') return null;
    const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(trimmed);
    const normalised = hasZone ? trimmed : `${trimmed}Z`;
    const parsed = new Date(normalised);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }

  dateTime(value: string | Date | null | undefined): string {
    const date = this.toDate(value);
    if (!date) return this.dash;
    return new Intl.DateTimeFormat(this.i18n.intlLocale(), {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: this.zone(),
    }).format(date);
  }

  date(value: string | Date | null | undefined): string {
    const date = this.toDate(value);
    if (!date) return this.dash;
    return new Intl.DateTimeFormat(this.i18n.intlLocale(), {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: this.zone(),
    }).format(date);
  }

  /**
   * A calendar date — `1991-04-02` — rendered as a day, not as an instant.
   *
   * A date of birth is a day on a calendar, not a moment in time. `date()` reads its
   * input as a timestamp, so `1991-04-02` becomes midnight UTC and lands on the 1st of
   * April in every timezone west of Greenwich: a birthday that quietly changes with
   * the reader's offset. A calendar date is therefore read as three numbers and never
   * moved, so the day on the record is the day on the screen.
   */
  calendarDate(value: string | null | undefined): string {
    if (!value) return this.dash;
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
    if (!match) return this.dash;
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    // Built in the reader's own zone and read back the same way, so the calendar day
    // is the day that was written down.
    const date = new Date(year, month - 1, day);
    if (
      Number.isNaN(date.getTime()) ||
      date.getFullYear() !== year ||
      date.getMonth() !== month - 1 ||
      date.getDate() !== day
    ) {
      return this.dash;
    }
    return new Intl.DateTimeFormat(this.i18n.intlLocale(), {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    }).format(date);
  }

  time(value: string | Date | null | undefined): string {
    const date = this.toDate(value);
    if (!date) return this.dash;
    return new Intl.DateTimeFormat(this.i18n.intlLocale(), {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: this.zone(),
    }).format(date);
  }

  /**
   * The queue-row stamp: a clock for today, a date beyond it.
   *
   * An operator scanning a queue needs "how long has this been waiting", and a
   * full timestamp on every row costs the width the message preview needs.
   * "Today" is computed in the viewer's zone, not the browser's local midnight
   * against a UTC string.
   */
  listStamp(value: string | Date | null | undefined, now: Date = new Date()): string {
    const date = this.toDate(value);
    if (!date) return this.dash;
    const zone = this.zone();
    const sameDay =
      this.dayKey(date, zone) === this.dayKey(now, zone);
    return sameDay ? this.time(date) : this.date(date);
  }

  /** A machine-comparable day key in the viewer's zone. */
  dayKey(value: Date, timeZone: string | undefined = this.zone()): string {
    return new Intl.DateTimeFormat('en-CA', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      timeZone,
    }).format(value);
  }

  /** A duration in seconds, as "1h 20m" / "45s" / "3d 4h". */
  duration(seconds: number | null | undefined): string {
    if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return this.dash;
    const total = Math.max(0, Math.floor(seconds));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const parts: string[] = [];
    if (days > 0) parts.push(this.unit('d', days));
    if (hours > 0) parts.push(this.unit('h', hours));
    if (minutes > 0 && days === 0) parts.push(this.unit('m', minutes));
    if (parts.length === 0) parts.push(this.unit('s', total));
    return this.durationUnits(parts);
  }

  /**
   * Duration with its unit label translated. The formatted numbers come from
   * `duration`; the words come from the caller, which owns the i18n layer.
   */
  durationWithLabels(
    seconds: number | null | undefined,
    labels: { day: string; hour: string; minute: string; second: string },
    joiner = ' ',
  ): string {
    if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return this.dash;
    const total = Math.max(0, Math.floor(seconds));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const parts: string[] = [];
    if (days > 0) parts.push(`${this.integer(days)} ${labels.day}`);
    if (hours > 0) parts.push(`${this.integer(hours)} ${labels.hour}`);
    if (minutes > 0 && days === 0) parts.push(`${this.integer(minutes)} ${labels.minute}`);
    if (parts.length === 0) parts.push(`${this.integer(total)} ${labels.second}`);
    return parts.join(joiner);
  }

  /** The active locale, for components that need it for `lang`/`dir` hints. */
  get locale(): Locale {
    return this.i18n.locale();
  }

  private toDate(value: string | Date | null | undefined): Date | null {
    if (value === null || value === undefined) return null;
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
    return this.parseApiTimestamp(value);
  }

  private unit(suffix: string, value: number): string {
    return `${this.integer(value)}${suffix}`;
  }

  private durationUnits(parts: string[]): string {
    return parts.join(' ');
  }
}
