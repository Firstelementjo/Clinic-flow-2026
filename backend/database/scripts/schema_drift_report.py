#!/usr/bin/env python3
"""
Phase-2 schema drift analyser.

Compares three independent descriptions of the FirstElement schema and emits
the evidence table used by docs/migration/schema-drift-report.md:

  1. the VERIFIED live database (PostgreSQL catalogue, read-only),
  2. packages/database/prisma/schema.prisma            (declared intent),
  3. packages/database/prisma/migrations/20260814000000_init/migration.sql
                                                        (recorded history),
  4. packages/database/prisma/migrations/20260814000100_vector_index/migration.sql.

Usage (Windows, Docker Desktop running):

    python backend/database/scripts/schema_drift_report.py
    python backend/database/scripts/schema_drift_report.py --json
    python backend/database/scripts/schema_drift_report.py --jdbc-only

READ-ONLY. It never writes to any database and never reads .env.
Connection details come from --container/--user/--database flags, all of which
default to the synthetic dev values already recorded in
docs/migration/opencode-run.md.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import OrderedDict

REPO_ROOT_MARKER = "backend/database/scripts"


# ---------------------------------------------------------------------------
# live database
# ---------------------------------------------------------------------------

LIVE_SQL = r"""
SELECT json_build_object(
  'enums', (SELECT coalesce(json_object_agg(e.typname, e.enum_values), '{}'::json)
              FROM (SELECT pt.typname,
                           string_agg(pe.enumlabel, ',' ORDER BY pe.enumsortorder) AS enum_values
                      FROM pg_type pt
                      JOIN pg_enum pe ON pe.enumtypid = pt.oid
                      JOIN pg_namespace pn ON pn.oid = pt.typnamespace
                     WHERE pn.nspname = 'public'
                     GROUP BY pt.typname) e),
  'tables', (SELECT coalesce(json_object_agg(c.relname,
                        (SELECT json_object_agg(a.attname,
                                    json_build_object(
                                        'type', format_type(a.atttypid, a.atttypmod),
                                        'notNull', a.attnotnull,
                                        'default', pg_get_expr(d.adbin, d.adrelid))
                                    ORDER BY a.attnum)
                          FROM pg_attribute a
                          LEFT JOIN pg_attrdef d
                                 ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                         WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)
                        ORDER BY c.relname), '{}'::json)
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind = 'r'),
  'extensions', (SELECT coalesce(json_object_agg(x.extname, x.extversion), '{}'::json)
                   FROM pg_extension x),
  'indexes', (SELECT coalesce(json_object_agg(y.indexname, y.indexdef), '{}'::json)
                FROM pg_indexes y WHERE y.schemaname = 'public'),
  'foreignKeys', (SELECT count(*) FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND con.contype = 'f'),
  'primaryKeys', (SELECT count(*) FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND con.contype = 'p')
)::text;
"""


def psql(container: str, user: str, database: str, sql: str) -> str:
    """Run a read-only query through `docker exec` and return stdout."""
    proc = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", database,
         "-t", "-A", "-P", "pager=off", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


# ---------------------------------------------------------------------------
# prisma schema.prisma
# ---------------------------------------------------------------------------

BLOCK = re.compile(r"^(model|enum)\s+(\w+)\s*\{(.*?)^\}", re.S | re.M)

# A field is a SCALAR column unless its type names another model or enum.
# Prisma relation fields (`project Project @relation(...)`) are virtual: they
# have no column of their own, so counting them as columns produces phantom
# drift against the live catalogue.
FIELD = re.compile(
    r"""^\s*(?P<name>\w+)\s+
         (?P<type>Unsupported\(\s*"[^"]+"\s*\)|\w+)
         (?P<list>\[\])?
         (?P<optional>\?)?
         (?P<attrs>\s+@.*)?$""",
    re.X,
)

# Prisma native-type annotations change the physical type without changing the
# Prisma scalar. @db.Uuid is the big one: this schema declares every UUID as
# `String @db.Uuid`, so ignoring the annotation would report 45 false drifts.
DB_ANNOTATION = re.compile(r"@db\.(\w+)(?:\(([^)]*)\))?")


def strip_trailing_comment(line: str) -> str:
    """Remove a trailing // comment that is not inside a quoted string.

    schema.prisma uses line comments on scalar fields, e.g.
        customer  String?    // customerId stored as string
    Those are real columns; without stripping, the field is invisible and the
    analyser reports phantom drift.
    """
    in_quotes = False
    for i, ch in enumerate(line):
        if ch == '"':
            in_quotes = not in_quotes
        elif ch == "/" and not in_quotes:
            if i + 1 < len(line) and line[i + 1] == "/":
                return line[:i]
    return line


def db_annotation_type(attrs: str | None) -> tuple[str, str] | None:
    if not attrs:
        return None
    m = DB_ANNOTATION.search(attrs)
    if not m:
        return None
    return m.group(1), (m.group(2) or "")


def parse_schema_prisma(text: str) -> dict:
    raw_blocks = [(kind, name, body) for kind, name, body in BLOCK.findall(text)]

    model_names = {name for kind, name, _ in raw_blocks if kind == "model"}
    enum_names = {name for kind, name, _ in raw_blocks if kind == "enum"}

    enums: "OrderedDict[str, list[str]]" = OrderedDict()
    models: "OrderedDict[str, dict]" = OrderedDict()

    for kind, name, body in raw_blocks:
        if kind == "enum":
            enums[name] = [
                line.strip()
                for line in body.splitlines()
                if line.strip() and not line.strip().startswith("//")
            ]
            continue

        table = name
        map_m = re.search(r'@@map\("([^"]+)"\)', body)
        if map_m:
            table = map_m.group(1)

        columns: "OrderedDict[str, dict]" = OrderedDict()
        for raw_line in body.splitlines():
            line = strip_trailing_comment(raw_line).strip()
            if not line or line.startswith("//") or line.startswith("@@"):
                continue
            m = FIELD.match(line)
            if not m:
                continue

            col_type = m.group("type")
            if col_type in model_names:
                continue  # relation field: no physical column
            is_list = bool(m.group("list"))
            if col_type in enum_names and is_list:
                continue  # enum array: a scalar column, but not a relation

            columns[m.group("name")] = {
                "type": col_type,
                "optional": bool(m.group("optional")),
                "list": is_list,
                "db": db_annotation_type(m.group("attrs")),
                "isId": "@id" in (m.group("attrs") or ""),
            }
        models[table] = columns

    return {"enums": enums, "tables": models}


# ---------------------------------------------------------------------------
# prisma init migration
# ---------------------------------------------------------------------------

CREATE_TABLE = re.compile(r'CREATE TABLE "([^"]+)" \((.*?)\n\);', re.S)
CREATE_TYPE = re.compile(
    r"CREATE TYPE \"(\w+)\" AS ENUM \((.*?)\);", re.S)


def parse_init_migration(text: str) -> dict:
    enums: "OrderedDict[str, list[str]]" = OrderedDict()
    for name, body in CREATE_TYPE.findall(text):
        enums[name] = re.findall(r"'([^']+)'", body)

    tables: "OrderedDict[str, list[str]]" = OrderedDict()
    for name, body in CREATE_TABLE.findall(text):
        cols: list[str] = []
        for line in body.split("\n"):
            line = line.strip()
            if not line or line.startswith("--"):
                continue
            m = re.match(r'"([^"]+)"\s', line)
            if m:
                cols.append(m.group(1))
        tables[name] = cols
    return {"enums": enums, "tables": tables}


def parse_vector_migration(text: str) -> list[str]:
    return re.findall(r'CREATE INDEX IF NOT EXISTS "([^"]+)"', text)


# ---------------------------------------------------------------------------
# comparison
# ---------------------------------------------------------------------------

PRISMA_TO_PG = {
    "String": ["text", "character varying"],
    "Int": ["integer"],
    "Float": ["double precision"],
    "Boolean": ["boolean"],
    "DateTime": ["timestamp(3) without time zone"],
    "Json": ["jsonb"],
    "Uuid": ["uuid"],
    "Bytes": ["bytea"],
    "Decimal": ["numeric"],
    "BigInt": ["bigint"],
}

PRISMA_SCALARS = {
    "String", "Int", "Float", "Boolean", "DateTime", "Json",
    "Uuid", "Bytes", "Decimal", "BigInt",
}


def expected_pg_types(prisma_type: str, is_list: bool, db: tuple | None) -> list[str]:
    if prisma_type.startswith("Unsupported("):
        # e.g. Unsupported("vector(1536)") -> vector(1536)
        inner = prisma_type[len("Unsupported("):].rstrip(")")
        return [inner.strip().strip('"')]
    if db:
        kind, arg = db
        if kind == "Uuid":
            return ["uuid"]
        if kind == "VarChar":
            return [f"character varying({int(arg)})"] if arg else ["character varying"]
        if kind == "Text":
            return ["text[]"] if is_list else ["text"]
        if kind == "Timestamp":
            return [f"timestamp({arg}) without time zone"] if arg else \
                   ["timestamp without time zone"]
        if kind == "Double":
            return ["double precision"]
        if kind == "Integer":
            return ["integer"]
    base = PRISMA_TO_PG.get(prisma_type)
    if base is None:
        # Enum column: the live type is the quoted enum name.
        return [prisma_type, '"%s"' % prisma_type]
    if is_list:
        # Prisma list of scalars is an array of the element type. Text[] is the
        # only array the current schema uses.
        return ["text[]"] if prisma_type == "String" else [f"{t}[]" for t in base]
    return base


def compare(repo_root: str) -> dict:
    schema_text = open(
        f"{repo_root}/packages/database/prisma/schema.prisma", encoding="utf-8").read()
    init_text = open(
        f"{repo_root}/packages/database/prisma/migrations/20260814000000_init/migration.sql",
        encoding="utf-8").read()
    vector_text = open(
        f"{repo_root}/packages/database/prisma/migrations/20260814000100_vector_index/migration.sql",
        encoding="utf-8").read()

    schema = parse_schema_prisma(schema_text)
    init = parse_init_migration(init_text)
    vector_indexes = parse_vector_migration(vector_text)

    return {"schema": schema, "init": init, "vector_indexes": vector_indexes}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--container", default="firstelement-postgres")
    parser.add_argument("--user", default="firstelement")
    parser.add_argument("--database", default="firstelement")
    parser.add_argument("--jdbc-only", action="store_true",
                        help="compare only the two Prisma sources (no database needed)")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()

    repo_root = args.repo_root
    if repo_root is None:
        here = __file__.replace("\\", "/")
        repo_root = here[: here.index(REPO_ROOT_MARKER)]

    parsed = compare(repo_root)
    schema, init = parsed["schema"], parsed["init"]

    result: dict = {
        "repo_root": repo_root,
        "prisma_schema": {
            "enums": len(schema["enums"]),
            "tables": len(schema["tables"]),
        },
        "init_migration": {
            "enums": len(init["enums"]),
            "tables": len(init["tables"]),
            "vector_indexes": parsed["vector_indexes"],
        },
    }

    # ---- live database ---------------------------------------------
    if not args.jdbc_only:
        live_raw = psql(args.container, args.user, args.database, LIVE_SQL)
        live = json.loads(live_raw)
        result["live"] = {
            "extensions": live["extensions"],
            "enums": len(live["enums"]),
            "tables": len(live["tables"]),
            "indexes": len(live["indexes"]),
            "foreign_keys": live["foreignKeys"],
            "primary_keys": live["primaryKeys"],
        }

        # --- tables: live vs prisma ---
        live_tables, prisma_tables = live["tables"], schema["tables"]
        result["tables_missing_in_live"] = sorted(set(prisma_tables) - set(live_tables))
        result["tables_missing_in_prisma"] = sorted(set(live_tables) - set(prisma_tables))

        column_mismatch = {}
        for table in sorted(set(live_tables) & set(prisma_tables)):
            live_cols, prisma_cols = live_tables[table], prisma_tables[table]
            missing = sorted(set(prisma_cols) - set(live_cols))
            extra = sorted(set(live_cols) - set(prisma_cols))
            nullability = sorted(
                c for c, meta in live_cols.items()
                if c in prisma_cols and prisma_cols[c]["optional"] and meta["notNull"])
            type_mismatch = {}
            for c in set(live_cols) & set(prisma_cols):
                want = expected_pg_types(prisma_cols[c]["type"],
                                          prisma_cols[c].get("list", False),
                                          prisma_cols[c].get("db"))
                if live_cols[c]["type"] not in want:
                    type_mismatch[c] = {"prisma": prisma_cols[c]["type"],
                                        "prismaDb": prisma_cols[c].get("db"),
                                        "live": live_cols[c]["type"]}
            if missing or extra or nullability or type_mismatch:
                column_mismatch[table] = {
                    "missing_in_live": missing,
                    "extra_in_live": extra,
                    "nullability_drift": nullability,
                    "type_drift": type_mismatch,
                }
        result["column_drift_live_vs_prisma"] = column_mismatch

        # --- enums: live vs prisma ---
        enum_drift = {}
        for name in sorted(set(schema["enums"]) & set(live["enums"])):
            prisma_vals = schema["enums"][name]
            live_vals = live["enums"][name].split(",")
            if prisma_vals != live_vals:
                enum_drift[name] = {"prisma": prisma_vals, "live": live_vals}
        result["enum_drift_live_vs_prisma"] = enum_drift
        result["enums_missing_in_live"] = sorted(set(schema["enums"]) - set(live["enums"]))

        # --- indexes ---
        live_indexes = set(live["indexes"])
        result["vector_indexes_missing_in_live"] = sorted(
            i for i in parsed["vector_indexes"] if i not in live_indexes)
        result["hnsw_indexes_in_live"] = sorted(
            i for i in live_indexes if "hnsw" in live["indexes"][i].lower())

        # Name-only comparison is not enough: the Prisma hand-written migration
        # asks for document_chunks_tenant_idx, but `prisma db push` created an
        # index over the SAME columns under a generated name. Report the
        # semantic state so a redundant duplicate index is not added later.
        def chunks_indexes_covering(cols: tuple[str, ...]) -> list[str]:
            found = []
            for name, definition in live["indexes"].items():
                if 'ON public.document_chunks' not in definition:
                    continue
                colpart = definition.split(" USING ", 1)[-1]
                if all(f'"{c}"' in colpart for c in cols):
                    found.append(name)
            return sorted(found)

        result["chunks_tenant_prefilter_indexes"] = chunks_indexes_covering(
            ("organizationId", "projectId", "knowledgeBaseId"))
        result["chunks_hnsw_indexes"] = sorted(
            n for n, d in live["indexes"].items()
            if 'ON public.document_chunks' in d and "hnsw" in d.lower())

    # --- tables: init migration vs prisma (no DB needed) ------------
    result["tables_missing_from_init"] = sorted(set(schema["tables"]) - set(init["tables"]))
    result["enums_missing_from_init"] = sorted(set(schema["enums"]) - set(init["enums"]))
    result["enum_drift_init_vs_prisma"] = {
        name: {"init": init["enums"][name], "prisma": schema["enums"][name]}
        for name in sorted(set(init["enums"]) & set(schema["enums"]))
        if init["enums"][name] != schema["enums"][name]
    }
    init_column_drift = {}
    for table in sorted(set(init["tables"]) & set(schema["tables"])):
        init_cols = set(init["tables"][table])
        prisma_cols = set(schema["tables"][table])
        if init_cols != prisma_cols:
            init_column_drift[table] = {
                "missing_in_init": sorted(prisma_cols - init_cols),
                "extra_in_init": sorted(init_cols - prisma_cols),
            }
    result["column_drift_init_vs_prisma"] = init_column_drift

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    def section(title: str) -> None:
        print()
        print("=" * 78)
        print(title)
        print("=" * 78)

    section("SHAPE")
    print(f"prisma schema.prisma : {result['prisma_schema']['tables']} tables, "
          f"{result['prisma_schema']['enums']} enums")
    print(f"init migration      : {result['init_migration']['tables']} tables, "
          f"{result['init_migration']['enums']} enums")
    if "live" in result:
        print(f"live database       : {result['live']['tables']} tables, "
              f"{result['live']['enums']} enums, {result['live']['indexes']} indexes, "
              f"{result['live']['foreign_keys']} FKs, {result['live']['primary_keys']} PKs")

    section("INIT MIGRATION vs schema.prisma")
    print(f"tables missing from init ({len(result['tables_missing_from_init'])}):")
    for t in result["tables_missing_from_init"]:
        print(f"  - {t}")
    print(f"enums missing from init ({len(result['enums_missing_from_init'])}): "
          f"{result['enums_missing_from_init']}")
    for name, drift in result["enum_drift_init_vs_prisma"].items():
        print(f"  ENUM DRIFT {name}:")
        print(f"      init  : {', '.join(drift['init'])}")
        print(f"      prisma: {', '.join(drift['prisma'])}")
    print(f"tables with column drift vs init ({len(result['column_drift_init_vs_prisma'])}):")
    for table, drift in result["column_drift_init_vs_prisma"].items():
        print(f"  {table}: missing={drift['missing_in_init']} extra={drift['extra_in_init']}")

    if "live" in result:
        section("LIVE DATABASE vs schema.prisma")
        print(f"tables missing in live : {result['tables_missing_in_live'] or 'none'}")
        print(f"tables missing in prisma: {result['tables_missing_in_prisma'] or 'none'}")
        print(f"enums missing in live  : {result['enums_missing_in_live'] or 'none'}")
        print(f"enum value drift       : "
              f"{list(result['enum_drift_live_vs_prisma']) or 'none'}")
        print(f"column drift tables    : "
              f"{list(result['column_drift_live_vs_prisma']) or 'none'}")
        section("VECTOR INDEXES")
        print(f"declared by Prisma hand-written migration: {parsed['vector_indexes']}")
        print(f"missing in live (by name): {result['vector_indexes_missing_in_live'] or 'none'}")
        print(f"hnsw indexes present in live        : {result['hnsw_indexes_in_live'] or 'none'}")
        print(f"tenant pre-filter indexes present   : {result['chunks_tenant_prefilter_indexes'] or 'none'}")
        print(f"  -> tenant pre-filter requirement is "
              f"{'ALREADY SATISFIED (different name, same columns)' if result['chunks_tenant_prefilter_indexes'] else 'NOT SATISFIED'}")
        print(f"  -> HNSW cosine index is "
              f"{'present' if result['hnsw_indexes_in_live'] else 'MISSING (additive migration V20260814000100 adds it)'}")

    section("VERDICT")
    if "live" in result:
        live_vs_prisma_clean = (
            not result["tables_missing_in_live"]
            and not result["tables_missing_in_prisma"]
            and not result["enums_missing_in_live"]
            and not result["enum_drift_live_vs_prisma"]
            and not result["column_drift_live_vs_prisma"]
        )
        print(f"live database == schema.prisma : "
              f"{'YES - baseline may be built from the live database' if live_vs_prisma_clean else 'NO - investigate'}")
        print(f"init migration usable as a baseline source : NO - quarantined")
        print(f"  reasons: {len(result['tables_missing_from_init'])} missing tables, "
              f"{len(result['enums_missing_from_init'])} missing enum(s), "
              f"{len(result['enum_drift_init_vs_prisma'])} enum value drift(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())