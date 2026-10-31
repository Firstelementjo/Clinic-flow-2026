/* global process, console */
// `node --test tests/shadow/finance-generations.test.mjs`
//
// Proves the fix in finance.mjs `partitionGenerations`, and -- the part that
// matters -- proves the PRE-FIX logic could not see an older generation at all.
//
// The pre-fix version is reproduced verbatim below and executed, rather than
// described. A regression guard that only asserts the new behaviour would pass
// just as happily if the fix had been made in the wrong place.

import test from 'node:test';
import assert from 'node:assert/strict';

import { partitionGenerations } from './finance.mjs';

const snap = (dimension, generation, extra = {}) => ({
  dimension,
  generation,
  cost: 10 * generation,
  revenue: 15 * generation,
  sourceEventCount: generation,
  supersedesSnapshotId: generation === 1 ? null : `gen-${generation - 1}`,
  ...extra,
});

/**
 * The pre-fix inline loop, verbatim. Ascending order, exactly as readSnapshots
 * returns it.
 */
function preFixPartition(snapshots) {
  const current = new Map();
  const frozenGenerations = [];
  for (const snapshot of snapshots) {
    const existing = current.get(snapshot.dimension);
    if (!existing || snapshot.generation > existing.generation) {
      current.set(snapshot.dimension, snapshot);
    } else {
      frozenGenerations.push(snapshot);
    }
  }
  return { current, older: frozenGenerations };
}

test('the pre-fix logic reported NO older generation for an ascending result', () => {
  const ascending = [snap('MESSAGE', 1), snap('MESSAGE', 2)];
  const before = preFixPartition(ascending);

  assert.deepEqual(
    before.older,
    [],
    'if this ever passes as non-empty the premise of the fix is wrong and must be re-examined',
  );
  assert.equal(before.current.get('MESSAGE').generation, 2);
});

test('the pre-fix logic also lost the predecessor at three generations', () => {
  const before = preFixPartition([snap('MESSAGE', 1), snap('MESSAGE', 2), snap('MESSAGE', 3)]);
  assert.deepEqual(before.older.map((s) => s.generation), []);
});

test('the fix reports generation 1 as older once generation 2 is current', () => {
  const { current, older } = partitionGenerations([snap('MESSAGE', 1), snap('MESSAGE', 2)]);

  assert.equal(current.get('MESSAGE').generation, 2);
  assert.deepEqual(older.map((s) => s.generation), [1]);
  assert.equal(older[0].supersedesSnapshotId, null);
  assert.equal(older[0].sourceEventCount, 1);
});

test('every generation except the maximum is preserved', () => {
  const { current, older } = partitionGenerations([
    snap('MESSAGE', 1),
    snap('MESSAGE', 2),
    snap('MESSAGE', 3),
  ]);
  assert.equal(current.get('MESSAGE').generation, 3);
  assert.deepEqual(older.map((s) => s.generation), [1, 2]);
});

test('it is order-independent, so a query that sorts differently cannot reintroduce the bug', () => {
  const ascending = [snap('MESSAGE', 1), snap('MESSAGE', 2), snap('MESSAGE', 3)];
  const descending = [...ascending].reverse();
  const shuffled = [ascending[2], ascending[0], ascending[1]];

  for (const input of [ascending, descending, shuffled]) {
    const { current, older } = partitionGenerations(input);
    assert.equal(current.get('MESSAGE').generation, 3, `wrong current for ${JSON.stringify(input.map((s) => s.generation))}`);
    assert.deepEqual(
      older.map((s) => s.generation),
      [1, 2],
      'older generations must not depend on arrival order',
    );
  }
});

test('dimensions are tracked independently', () => {
  const { current, older } = partitionGenerations([
    snap('MESSAGE', 1),
    snap('CONVERSATION', 1),
    snap('MESSAGE', 2),
  ]);

  assert.equal(current.get('MESSAGE').generation, 2);
  assert.equal(current.get('CONVERSATION').generation, 1);
  assert.deepEqual(older.map((s) => `${s.dimension}:${s.generation}`), ['MESSAGE:1']);
});

test('a single generation yields no older rows, which is the correct answer', () => {
  const { current, older } = partitionGenerations([snap('MESSAGE', 1)]);
  assert.equal(current.get('MESSAGE').generation, 1);
  assert.deepEqual(older, []);
});

test('an empty capture is empty rather than throwing', () => {
  const { current, older } = partitionGenerations([]);
  assert.equal(current.size, 0);
  assert.deepEqual(older, []);
});

test('older rows come back in a stable order for a readable diff', () => {
  // MESSAGE deliberately has generations 1 and 3 with no 2 -- a gap, as a partial
  // recompute would leave. SEAT has 1 and 2.
  const { older } = partitionGenerations([
    snap('SEAT', 2),
    snap('MESSAGE', 3),
    snap('SEAT', 1),
    snap('MESSAGE', 1),
  ]);
  assert.deepEqual(
    older.map((s) => `${s.dimension}:${s.generation}`),
    ['MESSAGE:1', 'SEAT:1'],
  );
});
