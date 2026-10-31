// @ts-check
import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/build/**',
      '**/coverage/**',
      'packages/database/generated/**',
      // The Angular workspace is a SEPARATE project with its own toolchain and its own
      // scripts, and it is not a member of this yarn workspace. `eslint .` from the root
      // walked into it and produced 2950 errors that say nothing about the legacy runtime:
      // this flat config does not understand Angular templates, signals or decorators, so
      // every one of those is noise. `legacy-node-static` ratchets the LEGACY tree, and
      // mixing a foreign project into that count made a passing ceiling unmeasurable.
      //
      // This is a scope correction, NOT a suppression, and the gap it exposes is real: the
      // Angular workspace currently has NO lint of its own (no eslint config, no lint
      // script, no lint stage). That is recorded in the migration ledger as an open quality
      // item rather than quietly absorbed here.
      'frontend/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // Build tooling (tailwind preset, postcss) is loaded by CommonJS
    // consumers and stays CJS on purpose.
    files: ['**/*.config.js', '**/*.config.cjs', 'packages/ui/tailwind-preset.js'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { module: 'writable', require: 'readonly', __dirname: 'readonly' },
    },
    rules: {
      '@typescript-eslint/no-require-imports': 'off',
    },
  },
  {
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // Tenant isolation: raw SQL is allowed only in the RAG layer, and only
      // through parameterized helpers. Flag string-concatenated SQL loudly.
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.property.name='$queryRawUnsafe']",
          message:
            'Use $queryRaw with tagged-template parameters. Raw unsafe SQL breaks tenant isolation guarantees.',
        },
      ],
    },
  },
);
