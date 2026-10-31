import { defineConfig } from '@hey-api/openapi-ts';

/**
 * The typed client is generated from the committed Spring contract at
 * `tests/contracts/openapi.json` — never from `openapi-paths.json`, which is
 * only a route inventory and carries no schemas.
 *
 * Why types only (`sdk: false`, `client: false`): the request layer has to run
 * through Angular's `HttpClient` so that the CSRF header interceptor, the
 * correlation-id interceptor and the error-contract interceptor all apply to
 * every call by construction. A generated `fetch` SDK would bypass that chain.
 * `src/app/core/api/` wraps the generated models in a hand-written typed
 * endpoint layer over `HttpClient`.
 *
 * The document is copied to `.openapi/source.json` by `npm run generate:api`
 * first, because the generator resolves `$ref` against the file it is given and
 * the committed contract must stay the single input.
 */
export default defineConfig({
  input: '.openapi/source.json',
  output: 'src/app/core/api/generated',
  plugins: [
    {
      name: '@hey-api/typescript',
      // Every field is optional-or-required exactly as the document says; the
      // `default-property-inclusion: non_null` setting on the Spring side means
      // an absent property is the normal case, so nothing is made required here.
      enums: 'javascript',
      definitions: true,
      operations: true,
      tree: true,
    },
  ],
});
