import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Rejects input that is empty *after trimming*.
 *
 * ## Why `Validators.required` is not enough
 *
 * `Validators.required` treats a string of spaces as present, because it only checks
 * `length`. So a note consisting of `"   "` passes validation and reaches
 * `POST /api/v1/notes` as a body of whitespace — an internal note that records nothing,
 * on a record where an operator believes they have written one. The reply composer had
 * the same hole, checked separately in the component with a hand-written `trim()`.
 *
 * One validator, used by both, so the two cannot disagree about what "empty" means.
 *
 * The error key is `blank` rather than `required`, so a template can tell "you have not
 * typed anything" from "what you typed is only whitespace" — the message is the same,
 * but a future copy change does not have to guess which case it is.
 */
export function notBlank(control: AbstractControl): ValidationErrors | null {
  const value = control.value;
  if (typeof value !== 'string') return null;
  return value.trim() === '' ? { blank: true } : null;
}

/**
 * `notBlank` wired for a reactive form, with its message deferred.
 *
 * Returns `null` rather than a function so it reads as a rule at the call site rather
 * than as plumbing.
 */
export function blankValidator(): ValidatorFn {
  return notBlank;
}
