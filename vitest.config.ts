import { config as loadDotenv } from 'dotenv';
import { defineConfig } from 'vitest/config';

// Vitest does not read `.env` on its own. Without this, DATABASE_URL is
// absent from the test process and every integration suite skips itself —
// silently, and forever, because "skipped" is not a failure. The suites
// that prove tenant isolation are exactly the ones that were never running.
loadDotenv();

export default defineConfig({
  test: {
    environment: 'node',
    include: ['packages/**/*.test.ts', 'apps/api/test/**/*.test.ts'],
    // Integration tests share one database; running suites in parallel
    // against it produces flakes that look like isolation bugs.
    fileParallelism: false,
    // The acceptance scenario ingests documents and runs AI turns end to
    // end, which is slower than a unit test has any right to be.
    testTimeout: 30_000,
    hookTimeout: 120_000,
  },
});
