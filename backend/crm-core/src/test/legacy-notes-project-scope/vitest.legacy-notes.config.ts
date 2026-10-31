/**
 * Standalone Vitest config for the legacy notes project-scope proof.
 *
 * The repository-level `vitest.config.ts` includes only `packages/**` and
 * `apps/api/test/**`, so this suite lives outside the default run on purpose:
 * `apps/**` is the frozen legacy reference and this harness must not add files
 * to it. `root` is the repository root so workspace packages
 * (`@firstelement/database`, `@firstelement/shared`, …) resolve exactly as the
 * legacy API resolves them.
 *
 * Run:
 *   npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts
 */
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  root: fileURLToPath(new URL('../../../../..', import.meta.url)),
  // The harness instantiates Nest classes (controllers, ValidationPipe), so
  // decorators and design-time metadata must be emitted. The repository's
  // TypeScript configs already do this for `apps/` and `packages/`; this
  // directory has no tsconfig of its own, so it is set here rather than by
  // adding a file the legacy build would pick up.
  esbuild: {
    tsconfigRaw: {
      compilerOptions: {
        experimentalDecorators: true,
        emitDecoratorMetadata: true,
        useDefineForClassFields: false,
        target: 'es2022',
      },
    },
  },
  test: {
    environment: 'node',
    include: ['backend/crm-core/src/test/legacy-notes-project-scope/**/*.test.ts'],
    testTimeout: 20_000,
  },
});
