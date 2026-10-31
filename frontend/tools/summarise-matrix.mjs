// One-off: reads test-results/full-matrix.json and prints the totals the report needs.
//
// Written as a file rather than an inline `node -e`, because the suite tree is recursive
// and a one-liner that stops at the first level under-counts by more than half — which is
// exactly the kind of wrong number that gets a run reported as green when it is not.
//
// Run with: `node tools/summarise-matrix.mjs`

import { readFileSync, writeFileSync } from 'node:fs';

const report = JSON.parse(readFileSync('test-results/full-matrix.json', 'utf8'));

/** Every test in the tree, with its project, its status, and how it ended. */
const tests = [];
const walk = (suite) => {
  for (const spec of suite.specs ?? []) {
    for (const entry of spec.tests ?? []) {
      const results = entry.results ?? [];
      const last = results[results.length - 1];
      tests.push({
        project: entry.projectName ?? '(unknown)',
        // The journey name lives on the **spec**, not on the test entry: a
        // `matrix()` test has `test.title === undefined`, and reading it there collapses
        // all 67 journeys into one bucket. Found the hard way — a summary that reported
        // "3 journeys" for a 402-test run was this line, not a real result.
        title: spec.title ?? entry.title ?? '(untitled)',
        file: spec.file ?? spec.location?.file ?? '(unknown)',
        line: spec.line ?? null,
        // `results.length > 1` means the test was retried. Recorded rather than hidden:
        // a retried-and-then-passed test is a different fact from a first-try pass.
        attempts: results.length,
        status: entry.status,
        outcome: last?.status ?? 'no-result',
        durationMs: last?.duration ?? 0,
      });
    }
  }
  for (const child of suite.suites ?? []) walk(child);
};
for (const suite of report.suites ?? []) walk(suite);

const tally = {};
for (const test of tests) {
  tally[test.outcome] = (tally[test.outcome] ?? 0) + 1;
}

const byProject = {};
for (const test of tests) {
  const entry = (byProject[test.project] ??= { total: 0, passed: 0, retried: 0 });
  entry.total += 1;
  if (test.outcome === 'passed') entry.passed += 1;
  if (test.attempts > 1) entry.retried += 1;
}

// Journeys measured on every one of the six combinations. Grouped by file + title,
// because a journey is identified by its name and the six rows that share it are the six
// measurements of one thing.
const combinations = Object.keys(byProject).sort();
const grouped = new Map();
for (const test of tests) {
  const key = `${test.file}:${test.line ?? '?'}::${test.title}`;
  const entry = grouped.get(key) ?? { title: test.title, file: test.file, seen: new Map() };
  entry.seen.set(test.project, test.outcome);
  grouped.set(key, entry);
}
const onAllSix = [];
const partial = [];
for (const entry of grouped.values()) {
  const seen = combinations.filter((name) => entry.seen.has(name));
  const allPassed = seen.length === combinations.length &&
    seen.every((name) => entry.seen.get(name) === 'passed');
  if (allPassed) onAllSix.push(entry);
  else partial.push({ ...entry, seen });
}

const summary = {
  statsFromReporter: report.stats,
  totalTestEntries: tests.length,
  tally,
  byProject,
  combinations,
  journeysTotal: grouped.size,
  measuredOnAllSix: onAllSix.length,
  notOnAllSix: partial.length,
  retriedTests: tests.filter((test) => test.attempts > 1).map((test) => ({
    project: test.project,
    title: test.title,
    attempts: test.attempts,
    outcome: test.outcome,
  })),
  notOnAllSixDetail: partial.map((entry) => ({
    title: entry.title,
    file: entry.file,
    seen: Object.fromEntries(entry.seen),
  })),
};

writeFileSync('test-results/full-matrix-summary.json', `${JSON.stringify(summary, null, 2)}\n`, 'utf8');

console.log(`reporter stats : ${JSON.stringify(report.stats)}`);
console.log(`tests found    : ${tests.length}`);
console.log(`tally          : ${JSON.stringify(tally)}`);
console.log(`by project     :`);
for (const [name, entry] of Object.entries(byProject)) {
  console.log(`  ${name.padEnd(12)} total=${entry.total} passed=${entry.passed} retried=${entry.retried}`);
}
console.log(`journeys       : ${grouped.size}`);
console.log(`on all six     : ${onAllSix.length}`);
console.log(`not on all six : ${partial.length}`);
console.log(`retried tests  : ${summary.retriedTests.length}`);
for (const entry of summary.retriedTests) {
  console.log(`  ${entry.project} "${entry.title}" attempts=${entry.attempts} -> ${entry.outcome}`);
}
if (partial.length > 0) {
  console.log('not on all six:');
  for (const entry of partial) console.log(`  ${entry.title} ${JSON.stringify(entry.seen)}`);
}
console.log('written: test-results/full-matrix-summary.json');