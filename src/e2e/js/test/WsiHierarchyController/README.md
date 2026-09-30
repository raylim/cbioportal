# WSI hierarchy E2E fixture notes

This E2E fixture is designed to be portable, minimal, and compatible with the
existing cBioPortal API E2E style. It validates the resource-data WSI model:
whole-slide images are generic `resource_data` rows, not native `wsi_*` tables.

The full-stack job (`scripts/run_wsi_full_stack_validation.sh`, driven by
`.github/workflows/wsi-validation.yml`) runs this fixture in both
unauthenticated local-bypass and authenticated WSI modes. The local tile
contract fixture validates the same source-bound version-2 JWT as the
companion tile server; it is not a public-mode production substitute. WSI is
login-only, including for public studies.

## Resource-data flow

1. `legacy_wsi_source/<fixture>/` holds the legacy `meta_wsi.txt` /
   `data_wsi.txt` (format v3) pair plus the study's other files. It is
   **converter input only**; Core `validateData.py` rejects a study that still
   contains the legacy pair.
2. `scripts/wsi_resource_fixtures.py --write --core-dir <cbioportal-core>`
   runs the pinned Core `scripts/importer/convertWsiToResources.py` with
   `--study-dir` and the CI `--portal-base-url http://localhost:8080`, and
   writes the importable study to `<fixture>/`:
   - `data_resource_definition.txt`: the `WSI_SAMPLE` and `WSI_PATIENT`
     resource definitions;
   - `data_resource_sample.txt`: sample-matched (`PART`/`BLOCK`) slides as
     `WSI_SAMPLE` rows;
   - `data_resource_patient.txt`: unmatched slides as `WSI_PATIENT` rows;
   - clinical sample/patient files with the seven `WSI_*` slide-count
     attributes merged in, including the patient attribute
     `WSI_PATIENT_UNDATED_SLIDE_COUNT` (viewable slides without a procedure
     day).

   Studies no longer load a `PATHOLOGY SLIDES` clinical-event (timeline)
   file. The v3 fixtures carry their timing on every row, which the converter
   writes into the resource METADATA, so no timeline file is kept, not even as
   converter input (format v2 input would need one).

   Every resource row has TYPE `WHOLE_SLIDE_IMAGE`, a viewer URL, and a
   METADATA JSON object with the public hierarchy, stain and timing fields and
   a private `wsi_serving` object (source URL, tile metadata, thumbnail URL and
   dimensions). A non-servable slide carries an empty `wsi_serving`.
3. CI regenerates the fixtures from the pinned Core and diffs them against the
   committed copy (`--check`), and runs `validateData.py` on the generated
   studies (`--validate`, zero errors required). After changing anything under
   `legacy_wsi_source/` or bumping the Core pin, rerun `--write` and commit the
   result.
4. The full-stack job imports `wsi_loader_fixture` and
   `wsi_loader_control_fixture` with Core `metaImport.py`, asserts the
   `resource_definition`/`resource_data` row counts, the undated count
   attribute, that no `PATHOLOGY SLIDES` events exist and that the native
   `wsi_*` tables stay empty, removes and reimports the public study, and then
   runs `WsiHierarchyController.spec.ts` and the frontend browser contracts.

`wsi_hierarchy_ci_seed.sql` is the equivalent direct ClickHouse seed (the same
resource rows with fixed `RESOURCE_DATA_ID`s) for isolated stacks that load
data without the importer. It adds one row the converter cannot produce: a
`WHOLE_SLIDE_IMAGE` row with a complete `wsi_serving` object under a non-WSI
resource (`WSI_CI_OTHER_SLIDES`, image `wsi-ci-other-slide`), which the access
endpoint must never serve.

## Public source

The fixture is anchored to the public `msk_spectrum_tme_2022` study and uses
publicly available patient/sample/slide identifiers from that study:

- patient `P-0055908`
- sample `P-0055908-T01-IM6`
- an unmatched pathology group with `sampleId: null`
- block-matched slide `3020726`
- part-matched slide `3020691`
- unmatched slide `3020648`
- viewable unmatched slide `3020649`

The fixture trims the live hierarchy down to one example per required match
level (`PART`, `BLOCK`, `UNMATCHED`). The two unmatched examples deliberately
cover both non-viewable and viewable source rows so clinical-data linkouts can
be tested without mutating API responses in the browser.

`WSI-CI-NO-SLIDES-PATIENT` (sample `WSI-CI-NO-SLIDES-SAMPLE`) is a synthetic
patient in the same study without any WSI rows; the hierarchy endpoint must
answer 200 with an empty `sampleGroups` list for it (an unknown patient is a
404).

The second study, `wsi_ci_study_b` (group `WSI_DENIED`), is an
authorization-negative control only; no private slide source or patient data
is used by the public fixture.

## Contract shape

`msk_spectrum_tme_2022.wsi_hierarchy.jsonl` is the expected
`GET /api/wsi/v2/hierarchy/{studyId}/{patientId}` response:

- `sampleGroups[].parts[].blocks[].slides[]`
- each slide carries its placement (`sampleId`, `matchLevel`, `specimenKey`,
  tile availability), timing, and its resource identity: `resourceId`
  (`WSI_SAMPLE` or `WSI_PATIENT`) and `resourceDataId`. The latter is
  allocated by the importer, so the fixture omits it and the spec asserts it
  is a positive integer before comparing the rest exactly.

Capabilities are requested by image ID:
`GET /api/wsi/v2/resources/{studyId}/{patientId}/access?imageId=<imageId>`
(a query parameter because image IDs may contain a slash) returns the exact
source URL, intrinsic tile metadata, thumbnail artifact, and a short-lived
capability. The lookup is restricted to the study, the patient, the
`WSI_SAMPLE`/`WSI_PATIENT` resources and TYPE `WHOLE_SLIDE_IMAGE`: an image of
another patient, an unknown image and the non-WSI row are 404s, a denied study
is a 403 and an anonymous request a 401. The tile server receives only that
bundle and serves `/tiles/zxy` and `/thumbnails`; it does not expose
hierarchy, search, patient, or slide metadata routes.

The generic resource table API (`/api/resource-table/tabs/fetch`,
`/api/resource-table/query/fetch`) lists the same WSI rows as ordinary
resources; the spec asserts its rows, columns and search never expose
`wsi_serving`, `s3://`, or the private serving paths.
