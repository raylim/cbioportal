-- ClickHouse base-table migration file.
--
-- Format: one section per db_schema_version, applied strictly in ascending order by
-- migrate_db.py (see migrate_db.py in this directory for the runner).
--
--   ## db_schema_version: <version>
--   ## description: <one line>
--   <SQL statements for this section>
--
-- Rules for writing a section:
--   1. Sections should be idempotent wherever possible (IF EXISTS / IF NOT EXISTS) — ClickHouse
--      has no transactions, so a crash mid-section ideally leaves it safe to re-run from the top.
--      Not always achievable; use judgment, and note in the description when a section isn't.
--   2. Never DROP a column in the same section that reads from it.
--   3. For ORDER BY / primary-key changes: create a new table, INSERT ... SELECT, RENAME —
--      ClickHouse cannot ALTER these in place.
--   4. Don't write db_schema_version updates yourself — migrate_db.py advances
--      info.db_schema_version automatically after a section's SQL succeeds (and waits for that
--      and any other mutations the section triggered to finish before treating it as applied).
--   5. If a section changes a table that feeds a derived table (see
--      db-scripts/clickhouse/populate_derived_tables.sql), no extra bookkeeping is needed here —
--      run migrate_db.py with --populate-derived-tables and it repopulates derived tables
--      automatically after any migration that actually applied something.
--
-- Sections for versions already recorded in the target database's info.db_schema_version are
-- skipped automatically — do not remove or renumber old sections once released.

## db_schema_version: 3.0.0
## description: ClickHouse-native migration era begins; collapse derived_table_schema_version into db_schema_version
ALTER TABLE info DROP COLUMN IF EXISTS derived_table_schema_version;

## db_schema_version: 3.1.0
## description: Reserved: native WSI snapshot tables (keyed by image id) were withdrawn before release
-- No changes. This version once created wsi_patient, wsi_part, wsi_block, wsi_slide and
-- wsi_slide_placement; 3.7.0 drops them from databases that applied it.

## db_schema_version: 3.2.0
## description: Reserved: native WSI snapshot tables were withdrawn before release
-- No changes. This version once rebuilt the native WSI tables; 3.7.0 drops them.

## db_schema_version: 3.3.0
## description: Reserved: native WSI snapshot tables were withdrawn before release
-- No changes. This version once replaced the wsi_slide constraints; 3.7.0 drops the table.

## db_schema_version: 3.4.0
## description: Reserved: WSI slide timing moves to the release that puts slides on the patient Summary timeline
-- No changes. This version once created wsi_slide_timing; 3.7.0 drops it.

## db_schema_version: 3.5.0
## description: Add unified resource_data table and backfill from legacy resource_sample/patient/study tables
-- Sorting key: patient_id and sample_id sit ahead of resource_data_id so the resource table's
-- default sort (ORDER BY patient_id, sample_id) is read in key order rather than sorting the whole
-- result set, which keeps paging cost independent of how large the resource is.
-- Both are Nullable (patient-level rows carry no sample; study-level rows carry neither), which
-- MergeTree only permits with allow_nullable_key.
CREATE TABLE IF NOT EXISTS resource_data
(
    `resource_data_id` Int64,
    `resource_id`      String,
    `cancer_study_id`  Int64,
    `resource_scope`   String,
    `patient_id`       Nullable(String),
    `sample_id`        Nullable(String),
    `url`              String,
    `display_name`     Nullable(String),
    `metadata`         Nullable(String)
) ENGINE = MergeTree ORDER BY (cancer_study_id, resource_id, patient_id, sample_id, resource_data_id)
  SETTINGS allow_nullable_key = 1;

-- resource_data_id is a running row number, not a hash of the row: a 64-bit hash is a
-- fingerprint rather than a unique id, the chance of two rows sharing one grows with the square
-- of the row count, and a collision would be absorbed silently by the NOT IN guard below --
-- losing a row on upgrade with no error. Row numbers are exact at any size. They start above the
-- current maximum, so a retry keeps the ids already issued and the importer carries on from
-- there: its sequence seeds from the table's maximum.
-- Re-running the section is safe because each insert skips rows whose natural key is already
-- present, which after a partial run restores exactly the rows that are missing. The study id is
-- part of that key: stable ids are unique only within a study, and the same URL can be attached
-- in more than one. Study-level rows key on the study's own internal_id, which already carries it.
-- Recreate the legacy tables if they are missing, so this section can be retried after a run
-- that reached the drops below but died before migrate_db.py advanced db_schema_version. On a
-- first run they already exist and this is a no-op; on a retry they come back empty, the
-- backfill finds nothing new, and the drops remove them again.
CREATE TABLE IF NOT EXISTS resource_sample (`internal_id` Int64, `resource_id` String, `url` String) ENGINE = MergeTree ORDER BY (internal_id, resource_id, url);
CREATE TABLE IF NOT EXISTS resource_patient (`internal_id` Int64, `resource_id` String, `url` String) ENGINE = MergeTree ORDER BY (internal_id, resource_id, url);
CREATE TABLE IF NOT EXISTS resource_study (`internal_id` Int64, `resource_id` String, `url` String) ENGINE = MergeTree ORDER BY (internal_id, resource_id, url);

INSERT INTO resource_data
    (resource_data_id, resource_id, cancer_study_id, resource_scope,
     patient_id, sample_id, url, display_name, metadata)
SELECT
    (SELECT ifNull(max(resource_data_id), 0) FROM resource_data) + row_number() OVER (),
    rs.resource_id,
    cs.cancer_study_id,
    'SAMPLE',
    p.stable_id,
    s.stable_id,
    rs.url,
    NULL, NULL
FROM resource_sample rs
INNER JOIN sample       s  ON rs.internal_id    = s.internal_id
INNER JOIN patient      p  ON s.patient_id      = p.internal_id
INNER JOIN cancer_study cs ON p.cancer_study_id = cs.cancer_study_id
WHERE (cs.cancer_study_id, rs.resource_id, s.stable_id, rs.url) NOT IN (
    SELECT (cancer_study_id, resource_id, sample_id, url) FROM resource_data WHERE resource_scope = 'SAMPLE'
);

INSERT INTO resource_data
    (resource_data_id, resource_id, cancer_study_id, resource_scope,
     patient_id, sample_id, url, display_name, metadata)
SELECT
    (SELECT ifNull(max(resource_data_id), 0) FROM resource_data) + row_number() OVER (),
    rp.resource_id,
    cs.cancer_study_id,
    'PATIENT',
    pt.stable_id,
    NULL,
    rp.url,
    NULL, NULL
FROM resource_patient rp
INNER JOIN patient      pt ON rp.internal_id     = pt.internal_id
INNER JOIN cancer_study cs ON pt.cancer_study_id = cs.cancer_study_id
WHERE (cs.cancer_study_id, rp.resource_id, pt.stable_id, rp.url) NOT IN (
    SELECT (cancer_study_id, resource_id, patient_id, url) FROM resource_data WHERE resource_scope = 'PATIENT'
);

INSERT INTO resource_data
    (resource_data_id, resource_id, cancer_study_id, resource_scope,
     patient_id, sample_id, url, display_name, metadata)
SELECT
    (SELECT ifNull(max(resource_data_id), 0) FROM resource_data) + row_number() OVER (),
    rst.resource_id,
    rst.internal_id,
    'STUDY',
    NULL, NULL,
    rst.url,
    NULL, NULL
FROM resource_study rst
WHERE (rst.internal_id, rst.resource_id, rst.url) NOT IN (
    SELECT (cancer_study_id, resource_id, url) FROM resource_data WHERE resource_scope = 'STUDY'
);

-- Deletions select rows by resource_data_id, so a duplicate id would make one row's deletion
-- take another row with it. Fail the migration rather than leave that in place.
SELECT throwIf(
    count() != uniqExact(resource_data_id),
    'resource_data backfill produced duplicate resource_data_id values'
) FROM resource_data;

-- Nothing reads the legacy split tables any more: the importer writes only resource_data, and
-- the API paths that used to read them (the legacy resource endpoints, the study resource
-- counts) were repointed. Dropping them leaves an upgraded database with the same schema a
-- fresh install gets from schema.sql, which no longer creates them.
DROP TABLE IF EXISTS resource_sample;
DROP TABLE IF EXISTS resource_patient;
DROP TABLE IF EXISTS resource_study;

## db_schema_version: 3.6.0
## description: Remove WSI data that exposes real image ids (wsi-serving-v5 opaque slide_key)
-- Pathology timeline events imported under wsi-serving-v4 carry real image ids (the IMAGE_IDS
-- attribute, and image-derived part/block keys inside LINKOUT specimenKey). Remove them;
-- re-importing the v5 timeline restores the links. position() is a literal substring match.
ALTER TABLE clinical_event_data DELETE
WHERE key = 'IMAGE_IDS'
   OR (key = 'LINKOUT' AND (position(value, 'image%3A') > 0 OR position(value, 'image:') > 0));
-- WSI resource rows written before slide_key existed name the slide by its real image id in url,
-- display_name and public metadata. The portal can neither list nor serve them (it addresses
-- slides only by slide_key), so delete them; re-importing the converted v3 resources restores the
-- slides. Both mutations are idempotent and safe to re-run.
ALTER TABLE resource_data DELETE
WHERE resource_id IN ('WSI_SAMPLE', 'WSI_PATIENT')
  AND JSONExtractString(ifNull(metadata, '{}'), 'slide_key') = '';

## db_schema_version: 3.7.0
## description: Remove pathology image ids and object URIs from the database (wsi-serving-v6 sealed source)
-- The pathology image id must not be stored in cBioPortal, and object URIs embed it. Drop the
-- withdrawn native WSI tables, which are keyed by image id, from databases that applied an earlier
-- 3.1.0-3.4.0, and the release-based tables that preceded them. Nothing reads any of them.
DROP TABLE IF EXISTS wsi_slide_timing SYNC;
DROP TABLE IF EXISTS wsi_slide_placement SYNC;
DROP TABLE IF EXISTS wsi_slide SYNC;
DROP TABLE IF EXISTS wsi_block SYNC;
DROP TABLE IF EXISTS wsi_part SYNC;
DROP TABLE IF EXISTS wsi_patient SYNC;
DROP TABLE IF EXISTS wsi_release_patient SYNC;
DROP TABLE IF EXISTS wsi_release SYNC;
-- WSI resource rows imported under wsi-serving-v5 keep the image id and the source/thumbnail
-- URIs in metadata (wsi_serving, or the legacy top-level image_id). v6 rows carry only the opaque
-- wsi_serving.sealed_source, so delete the older rows; re-importing the study's v6 WSI resources
-- restores the slides. The mutation is idempotent and safe to re-run.
ALTER TABLE resource_data DELETE
WHERE resource_id IN ('WSI_SAMPLE', 'WSI_PATIENT')
  AND (JSONHas(ifNull(metadata, '{}'), 'image_id')
    OR JSONHas(ifNull(metadata, '{}'), 'wsi_serving', 'image_id')
    OR JSONHas(ifNull(metadata, '{}'), 'wsi_serving', 'source_url')
    OR JSONHas(ifNull(metadata, '{}'), 'wsi_serving', 'thumbnail_url'));

## db_schema_version: 3.8.0
## description: Bring resource_data created by an earlier 3.5.0 to the current shape (resource_scope, no type, Int64 cancer_study_id)
-- 3.5.0 was revised after some databases applied it: entity_type became resource_scope, the type
-- column was dropped in favour of METADATA, and cancer_study_id became Int64. A database that ran
-- the revised 3.5.0 already has that shape, and every statement below is then a no-op or an
-- exact copy, so the section is safe on any database and on a retry.
ALTER TABLE resource_data RENAME COLUMN IF EXISTS entity_type TO resource_scope;
ALTER TABLE resource_data DROP COLUMN IF EXISTS type;
-- cancer_study_id leads the sorting key, and ClickHouse forbids widening a key column in place, so
-- the table is rebuilt. Rows already copied by an interrupted run are skipped; the old table is
-- dropped only after the rename, and a leftover one from an interrupted run is dropped first.
CREATE TABLE IF NOT EXISTS resource_data_rebuild
(
    `resource_data_id` Int64,
    `resource_id`      String,
    `cancer_study_id`  Int64,
    `resource_scope`   String,
    `patient_id`       Nullable(String),
    `sample_id`        Nullable(String),
    `url`              String,
    `display_name`     Nullable(String),
    `metadata`         Nullable(String)
) ENGINE = MergeTree ORDER BY (cancer_study_id, resource_id, patient_id, sample_id, resource_data_id)
  SETTINGS allow_nullable_key = 1;
INSERT INTO resource_data_rebuild
    (resource_data_id, resource_id, cancer_study_id, resource_scope,
     patient_id, sample_id, url, display_name, metadata)
SELECT resource_data_id, resource_id, toInt64(cancer_study_id), resource_scope,
       patient_id, sample_id, url, display_name, metadata
FROM resource_data
WHERE resource_data_id NOT IN (SELECT resource_data_id FROM resource_data_rebuild);
SELECT throwIf(
    (SELECT count() FROM resource_data) != (SELECT count() FROM resource_data_rebuild),
    'resource_data rebuild row count mismatch'
);
DROP TABLE IF EXISTS resource_data_pre_3_8_0 SYNC;
RENAME TABLE resource_data TO resource_data_pre_3_8_0, resource_data_rebuild TO resource_data;
DROP TABLE IF EXISTS resource_data_pre_3_8_0 SYNC;
