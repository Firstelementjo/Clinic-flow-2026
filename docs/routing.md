# Routing

An escalation that nobody owns is an escalation that did not happen.
Routing turns "this needs a person" into "this is Layla's", or says
plainly why it could not.

## The pipeline

Deterministic, and stated in one place:

```
project membership → department → required skill → availability status
  → working hours → workload headroom → strategy
```

Every stage is a named filter, so an unassignable escalation reports
*which* stage emptied the list. "Nobody is on shift" and "everyone is at
capacity" are different problems for an ops manager at 9pm, and a router
that cannot tell them apart is not much use.

Membership is first on purpose: routing must never hand a conversation to
someone who would get a 404 opening it.

`BUSY` stays eligible — busy is a person mid-task, not one who has gone
home. The workload filter decides whether they can take another.

## Determinism

Given the same escalation and the same roster, routing always picks the
same person. Ties break on skill level, then on employee id. An ops
manager has to be able to answer "why did this go to Layla?", and a router
that shuffles under a tie cannot be tested, tuned or trusted.

## Strategies

`LEAST_LOADED` ships and is the default: fewest active conversations wins.
`ROUND_ROBIN` is selectable and currently routes least-loaded — it needs a
persisted per-project cursor to be genuinely round-robin, and the code says
so rather than pretending. Priority and custom-rule strategies plug into
the same `RoutingStrategyImpl` interface.

## Workload

`Employee.activeConversationCount` is **recounted**, never incremented and
decremented. A counter that misses one write because a process died
between two statements drifts permanently, and a drifted workload silently
starves the busiest agent or overloads them. Recounts happen on
assignment, manual reassignment, and resolution; the query is indexed and
cheap.

## Working hours

Windows are a local weekday plus minutes from local midnight, in the
employee's own IANA zone — not instants. Working hours are a recurring
local-time concept, and storing them as timestamps makes every
daylight-saving change a data migration, which the Levant half of this
market goes through twice a year. `Intl` does the conversion, including
DST.

A window whose end is before its start crosses midnight, so a 22:00–06:00
night shift matches both halves. **No windows at all means always
available** — an organization that has not configured schedules yet should
still receive escalations rather than watching a queue that looks broken.

## Routing rules

Rules map what a conversation is about to who should handle it: match on
`intent` and/or `department` (null is a wildcard), require a `skill`, pick
a strategy. First match by ascending priority wins, ties broken by id.

With no rules configured, everyone in the project is a candidate and
least-loaded decides — the sensible default rather than an empty queue.

## The unassigned queue

When nobody qualifies, the escalation stays `PENDING` with no assignee and
the blocking stage logged. The job is **not** retried: nobody becomes
available because a job ran again. The conversation waits in the inbox's
Unassigned view until someone takes it or the roster changes.

## Employee import

Upload → preview → map columns → validate → show errors → confirm →
import (plan §10). CSV natively, XLSX through exceljs.

The rule that shapes it: **never blindly import malformed rows.** A silent
skip means an ops manager believes 40 agents exist when 37 do, and finds
out when escalations start queueing. So:

- Every row is either valid or carries a row-numbered reason
  (`Row 12: "many" must be a whole number between 1 and 200`).
- Existing emails and in-file repeats are reported as duplicates, not
  merged and not dropped — the same person listed twice usually means two
  different intended records.
- Column mapping is guessed from English *and* Arabic headers, and the
  admin can correct it.
- Nothing is written until the admin confirms.

Imported accounts get a random password nobody is told. The account exists
but is unusable until a reset, so an import cannot quietly create logins
with a predictable secret.

## What is not here yet

Notifications. An assigned employee is not told — no email, no push, no
realtime badge. Slice 9 adds that, and it is the gap most likely to matter
in a live deployment: routing works, but somebody has to be looking at the
inbox to notice.
