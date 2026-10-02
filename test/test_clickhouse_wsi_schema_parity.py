#!/usr/bin/env python3
"""Keep the fresh-install and migrated WSI resource-data schemas identical.

WSI is served from the generic ``resource_data`` table (RESOURCE_ID
``WSI_SAMPLE``/``WSI_PATIENT``, TYPE ``WHOLE_SLIDE_IMAGE``).  This checks that
``init/schema.sql`` and the ``3.5.0`` section of ``migrate/migrate_schema.sql``
create the same ``resource_data`` table with the same sorting key, that the
fresh schema is seeded at the version the migration ends on, and that the
legacy split resource tables the 3.5.0 backfill consumes are dropped.

The native ``wsi_*`` tables are no longer read by the backend; the only check
kept for them is that a fresh install still creates them (so a later cleanup
is a deliberate schema change, not an accident).

Run directly (``python3 test_clickhouse_wsi_schema_parity.py``) or under
pytest.  ``WSI_SCHEMA_SOURCE_DIR`` selects the backend checkout to inspect
(default: this repository).
"""

from __future__ import annotations

import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("WSI_SCHEMA_SOURCE_DIR", Path(__file__).resolve().parents[1]))
FRESH = ROOT / "src/main/resources/db-scripts/clickhouse/init/schema.sql"
MIGRATION = ROOT / "src/main/resources/db-scripts/clickhouse/migrate/migrate_schema.sql"
POM = ROOT / "pom.xml"

RESOURCE_DATA_VERSION = "3.5.0"
RESOURCE_DATA_COLUMNS = [
    ("RESOURCE_DATA_ID", "Int64"),
    ("RESOURCE_ID", "String"),
    ("CANCER_STUDY_ID", "Int32"),
    ("ENTITY_TYPE", "String"),
    ("PATIENT_ID", "Nullable(String)"),
    ("SAMPLE_ID", "Nullable(String)"),
    ("URL", "String"),
    ("DISPLAY_NAME", "Nullable(String)"),
    ("TYPE", "Nullable(String)"),
    ("METADATA", "Nullable(String)"),
]
RESOURCE_DATA_KEY = ["CANCER_STUDY_ID", "RESOURCE_ID", "PATIENT_ID", "SAMPLE_ID", "RESOURCE_DATA_ID"]
LEGACY_RESOURCE_TABLES = ("resource_sample", "resource_patient", "resource_study")
NATIVE_WSI_TABLES = (
    "wsi_patient",
    "wsi_part",
    "wsi_block",
    "wsi_slide",
    "wsi_slide_placement",
    "wsi_slide_timing",
)
CREATE = re.compile(
    r"CREATE TABLE(?: IF NOT EXISTS)?\s+`?(\w+)`?\s*\((.*?)\)\s*ENGINE\s*=\s*([^;]*);",
    re.DOTALL | re.IGNORECASE,
)


def strip_comments(sql: str) -> str:
    return re.sub(r"--[^\n]*", "", sql)


def tables(sql: str) -> dict[str, tuple[str, str]]:
    """Map table name to (column body, engine clause) for every CREATE TABLE."""
    return {m.group(1): (m.group(2), m.group(3)) for m in CREATE.finditer(strip_comments(sql))}


def columns(body: str) -> list[tuple[str, str]]:
    result = []
    for line in body.split(","):
        line = " ".join(line.replace("`", "").split())
        if not line:
            continue
        name, _, column_type = line.partition(" ")
        result.append((name, column_type))
    return result


def sorting_key(engine: str) -> list[str]:
    match = re.search(r"ORDER BY\s*\(([^)]*)\)", engine)
    if not match:
        raise AssertionError(f"resource_data engine has no ORDER BY tuple: {engine}")
    return [part.strip().strip("`") for part in match.group(1).split(",")]


def normalized_engine(engine: str) -> str:
    return " ".join(engine.split())


def migration_section(sql: str, version: str) -> str:
    marker = f"## db_schema_version: {version}"
    if marker not in sql:
        raise AssertionError(f"migrate_schema.sql has no {version} section")
    section = sql.split(marker, 1)[1]
    return section.split("## db_schema_version:", 1)[0]


def last_migration_version(sql: str) -> str:
    versions = re.findall(r"^## db_schema_version: (\S+)", sql, re.MULTILINE)
    if not versions:
        raise AssertionError("migrate_schema.sql declares no db_schema_version sections")
    return versions[-1]


def test_resource_data_matches_between_fresh_and_migrated_schema() -> None:
    fresh_sql = FRESH.read_text(encoding="utf-8")
    migration_sql = MIGRATION.read_text(encoding="utf-8")
    fresh = tables(fresh_sql)
    section = tables(migration_section(migration_sql, RESOURCE_DATA_VERSION))

    assert "resource_data" in fresh, f"{FRESH} does not create resource_data"
    assert "resource_data" in section, (
        f"the {RESOURCE_DATA_VERSION} migration section does not create resource_data"
    )
    fresh_body, fresh_engine = fresh["resource_data"]
    migrated_body, migrated_engine = section["resource_data"]

    assert columns(fresh_body) == RESOURCE_DATA_COLUMNS, columns(fresh_body)
    assert columns(migrated_body) == RESOURCE_DATA_COLUMNS, columns(migrated_body)
    assert sorting_key(fresh_engine) == RESOURCE_DATA_KEY, sorting_key(fresh_engine)
    assert sorting_key(migrated_engine) == RESOURCE_DATA_KEY, sorting_key(migrated_engine)
    assert normalized_engine(fresh_engine) == normalized_engine(migrated_engine), (
        f"fresh: {fresh_engine}\nmigrated: {migrated_engine}"
    )
    for engine in (fresh_engine, migrated_engine):
        # PATIENT_ID/SAMPLE_ID are Nullable key columns.
        assert "allow_nullable_key = 1" in " ".join(engine.split()), engine


def test_resource_definition_is_created_by_a_fresh_install() -> None:
    fresh = tables(FRESH.read_text(encoding="utf-8"))
    assert "resource_definition" in fresh
    names = [name for name, _ in columns(fresh["resource_definition"][0])]
    for required in ("resource_id", "resource_type", "cancer_study_id", "custom_metadata"):
        assert required in names, f"resource_definition lacks {required}: {names}"


def test_migration_retires_the_legacy_split_resource_tables() -> None:
    fresh = tables(FRESH.read_text(encoding="utf-8"))
    section = migration_section(MIGRATION.read_text(encoding="utf-8"), RESOURCE_DATA_VERSION)
    for table in LEGACY_RESOURCE_TABLES:
        assert table not in fresh, f"fresh schema still creates {table}"
        assert re.search(rf"DROP TABLE IF EXISTS {table}\s*;", section), (
            f"{RESOURCE_DATA_VERSION} does not drop {table}"
        )
        assert re.search(rf"FROM {table}\b", section), (
            f"{RESOURCE_DATA_VERSION} does not backfill resource_data from {table}"
        )


def test_fresh_schema_is_seeded_at_the_final_migration_version() -> None:
    fresh_sql = FRESH.read_text(encoding="utf-8")
    migration_sql = MIGRATION.read_text(encoding="utf-8")
    seeded = re.search(
        r"INSERT INTO info\s*\([^)]*\)\s*VALUES\s*\(\s*'([^']+)'", fresh_sql, re.IGNORECASE
    )
    assert seeded, "schema.sql does not seed info.db_schema_version"
    final = last_migration_version(migration_sql)
    assert seeded.group(1) == final == RESOURCE_DATA_VERSION, (
        f"schema.sql seeds {seeded.group(1)}, migration ends at {final}, "
        f"validation expects {RESOURCE_DATA_VERSION}"
    )
    if POM.is_file():
        pom_version = re.search(r"<db\.version>([^<]+)</db\.version>", POM.read_text(encoding="utf-8"))
        assert pom_version and pom_version.group(1) == RESOURCE_DATA_VERSION, pom_version


def test_native_wsi_tables_are_still_created_but_unread() -> None:
    fresh = tables(FRESH.read_text(encoding="utf-8"))
    missing = [table for table in NATIVE_WSI_TABLES if table not in fresh]
    assert not missing, f"native WSI tables disappeared from schema.sql: {missing}"
    mappers = ROOT / "src/main/resources/mappers/clickhouse/wsi"
    if mappers.is_dir():
        for mapper in sorted(mappers.glob("*.xml")):
            text = mapper.read_text(encoding="utf-8")
            for table in NATIVE_WSI_TABLES:
                assert not re.search(rf"\b(?:FROM|JOIN)\s+{table}\b", text), (
                    f"{mapper.name} still reads native table {table}"
                )


if __name__ == "__main__":
    tests = [value for name, value in sorted(globals().items()) if name.startswith("test_")]
    for test in tests:
        test()
        print(f"ok {test.__name__}")
    print("ClickHouse fresh/migration WSI resource-data schema parity passed")
