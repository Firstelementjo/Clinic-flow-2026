# Knowledge base and retrieval

Business knowledge is project-scoped, always (plan §20.4). A knowledge base
belongs to one project, and retrieval can never reach past it.

## Pipeline

```
upload → store → parse → clean → chunk → embed → store vectors → READY
```

Everything after `store` runs in the worker: embedding a 40-page PDF is not
something an HTTP request should wait on. Status moves
`UPLOADED → PROCESSING → INDEXING → READY | FAILED`, and the UI shows each
step because "nothing has happened yet" and "this failed" look identical
otherwise.

Processing is re-runnable. A retried job deletes the chunks it already
wrote before writing them again, so a partial failure cannot leave a
document half-indexed and answering with fragments.

## The isolation rule

**The project filter runs before the similarity search, not after.**

```sql
WHERE c."organizationId" = $1 AND c."projectId" = $2 AND d.status = 'READY'
ORDER BY c.embedding <=> $query
LIMIT $k
```

Filtering afterwards would rank one tenant's chunks against another's and
merely hide the losers: a top-k that silently dropped the correct answer,
and a query plan that touched data the caller had no right to read. Chunks
therefore carry `organizationId`, `projectId`, `knowledgeBaseId` and
`documentId` of their own rather than relying on a join.

`apps/api/test/knowledge-retrieval.integration.test.ts` proves it with two
projects holding contradictory refund policies.

## Parsing

| Type | Handling |
| --- | --- |
| `text/plain`, `text/markdown` | As-is |
| `text/csv` | Each row becomes `header: value` pairs — a bare grid of numbers embeds as noise |
| `application/json` | Flattened to `path: value` lines so structure survives |
| `application/pdf` | `pdf-parse`; a scan with no text layer fails with a message saying it needs OCR |
| `.docx` | `mammoth` raw text |

Uploads are capped at 20MB and restricted to those types. Storage keys are
generated (`projects/<projectId>/documents/<uuid>`), never derived from the
uploaded filename — user input on a filesystem path is a traversal bug
waiting to happen. The original name is kept in the database as a label.

## Chunking

Structure first: paragraphs, then sentences (including `؟` and `۔`), and
only then a hard character cut. Chunk boundaries decide what the AI can
cite — a refund policy split mid-sentence retrieves as two half-answers,
and the agent either invents the missing half or escalates something it
should have handled. 200 characters of overlap carry forward so a fact
straddling a boundary appears whole in at least one chunk.

Defaults: 1,500 characters target, 2,400 ceiling, 200 overlap.

## Embeddings

`EMBEDDING_PROVIDER` selects the implementation; the pgvector column is
fixed at 1536 dimensions and a provider returning a different width is
rejected rather than silently corrupting retrieval.

- **`mock`** (default) — deterministic hashed bag-of-words, no API key, no
  network. Not random vectors: texts sharing vocabulary genuinely land near
  each other, so retrieval is testable end to end in development and CI. It
  is lexical, not semantic — it will not connect "reimbursement" to
  "refund".
- **`openai`** — `text-embedding-3-small` at 1536 dimensions over REST.

A missing key is a startup error, not a silent fallback to the mock: an
environment that believes it is embedding with OpenAI while producing
hashed bag-of-words vectors gives plausible-looking, quietly useless
retrieval.

## Scores and knowledge gaps

pgvector's `<=>` is cosine distance; the API reports similarity
(`1 - distance`) in `[0, 1]`. The default floor is 0.25 — below it a chunk
is treated as noise, because a weak match is worse than no match: the agent
answers confidently from an irrelevant document.

When nothing clears the floor, retrieval returns `isGap: true` and a
`KnowledgeGap` row records the question, the best score achieved, and the
conversation it came from. That backlog is what tells an admin which
documents are missing, and it is why an unanswerable question becomes a
data point instead of just an escalation. In slice 5 the agent asks a
clarifying question or escalates on a gap — it never invents business
policy (plan §9).

## Index

`prisma/migrations/20260814000100_vector_index` is hand-written because
Prisma's schema language cannot express pgvector index operators. It builds
an HNSW index over cosine distance — no training step, and correct as rows
are added continuously, which is how documents actually arrive. The index
only accelerates ordering; isolation is the WHERE clause, never the index.

## Trying it

```bash
docker compose up -d postgres redis && yarn db:push && yarn db:seed
```

Open a project's Knowledge page, paste a policy or upload a file, wait for
`READY`, then use **Test retrieval** to see exactly what the agent would
find, with scores — before a customer asks.
