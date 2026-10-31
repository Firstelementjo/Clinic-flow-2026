# ADR 0017: A bilingual document is not a mismatch

- Status: accepted
- Date: 2026-10-07
- Phase: 16
- Scope: `locale` on an export, versus the locale of the document template it renders through
- Raised by: `firstelement-exports`
- Decided by: orchestrator

## Context

Phase 16 made `locale` a validated, recorded, honoured parameter on an export.
It left one case unresolved and escalated it rather than deciding quietly, which
was the right call:

> A DOCX export is not checked against its template's locale. Pinning
> `locale: en` against an Arabic template yields an English render of
> Arabic-labelled operator prose.

This reads like a bug. It is not, and the reason matters.

## The two locales are different things

- **The data locale** — the language of the column headers, the values, the
  status words, the dates and the currency formatting in the rendered table.
- **The template locale** — the language of the document's own prose: the
  heading, the explanatory paragraph, the sign-off, the boilerplate an author
  typed into the template when they uploaded it.

They are separable because this product is **Arabic-first with English-speaking
operators**. A realistic and desirable document is an English-language operator
report whose table carries Arabic-labelled clinical data, or an Arabic cover
sheet wrapping an English data section. Refusing the pairing would make those
documents impossible to produce at all.

So the export honours both independently: data in the pinned `locale`, prose in
whatever language the template was authored in. The agent had already arranged
for the pinned locale to be recorded in the job's authorization snapshot and
honoured at generation, with the report definition's default as the fallback
when a request expresses no preference.

## Decision

**Do not refuse a template/data locale mismatch. Produce the document, and
record both locales so a reader can tell what they are looking at.**

## Conditions

1. **The catalogue must publish the template version's locale.** It does. This
   is what makes the pairing an informed choice by the client rather than a
   silent surprise.
2. **The job snapshot must record both** the pinned data locale and the template
   locale actually used. A reader who opens the artefact and sees two languages
   must be able to establish which is which without guessing. If only one is
   recorded, this ADR's outcome is indistinguishable from a bug and someone
   will "fix" it by adding a refusal.
3. **Both must reach the audit trail**, not just the job row. An export of
   clinical or financial data is evidence, and evidence must say what it was.

## Why not refuse

Refusing is the safer-sounding option and it is wrong here. It would:

- make bilingual documents unproducible, in a product whose stated design is
  Arabic-first with English-speaking staff;
- push the problem into a client, which would then have to guess which locale to
  send rather than being told;
- convert a documented, published capability (the catalogue advertises each
  template's locale) into a runtime error, which is the opposite of the honesty
  the catalogue was built to provide.

The honest alternative to refusing is **disclosure**: publish the pairing, record
it, and let the author choose.

## Known residual

The rendered document does not currently label its own two locales to the
reader. A reader seeing mixed Arabic and English in one document may not know
which language governs the prose. Condition 2 addresses the audit side of this;
the in-document labelling is a template-authoring concern and is left to the
template, not to the export job.

## Alternatives rejected

1. **Refuse the mismatch with a 4xx.**
   Rejected for the reasons above. Recorded in the ADR so a later reader who
   thinks it looks wrong does not "correct" it.

2. **Force the data locale to the template's locale, ignoring the request.**
   Rejected. It is the same defect as silently ignoring an unknown filter key: a
   request parameter that is accepted and then discarded. Silent wrong output is
   worse than a refusal in a finance-adjacent subsystem.

3. **Ignore the template's locale entirely and render prose in the data locale.**
   Rejected. It would require rewriting authored prose at render time, discarding
   what the author actually uploaded.