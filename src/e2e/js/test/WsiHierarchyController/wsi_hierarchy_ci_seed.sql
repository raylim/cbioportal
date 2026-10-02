-- Portal catalog identities for the WSI resource-data CI seed.
-- This seed mirrors what importing the generated wsi_loader_fixture and
-- wsi_loader_control_fixture studies produces (see scripts/wsi_resource_fixtures.py):
-- WSI is stored only as resource_definition + resource_data rows
-- (RESOURCE_ID WSI_SAMPLE/WSI_PATIENT, TYPE WHOLE_SLIDE_IMAGE, METADATA with a
-- private wsi_serving object). The native wsi_* tables are intentionally left
-- empty: the backend no longer reads them.
-- Keep the small catalog dimensions here because an isolated ClickHouse stack
-- loads the WSI fixture on top of the schema without the production seed.
INSERT INTO reference_genome
  (reference_genome_id, species, name, build_name, genome_size, url, release_date)
SELECT *
FROM (
  SELECT toInt64(1) AS reference_genome_id, 'human' AS species, 'hg19' AS name,
         'GRCh37' AS build_name, toNullable(toInt64(2897310462)) AS genome_size,
         'http://hgdownload.cse.ucsc.edu/goldenPath/hg19/bigZips',
         toDateTime64('2009-02-01 00:00:00', 6) AS release_date
  UNION ALL
  SELECT toInt64(2), 'human', 'hg38', 'GRCh38', toNullable(toInt64(3049315783)),
         'http://hgdownload.cse.ucsc.edu/goldenPath/hg38/bigZips',
         toDateTime64('2013-12-01 00:00:00', 6)
) AS seed
WHERE reference_genome_id NOT IN (SELECT reference_genome_id FROM reference_genome);

INSERT INTO type_of_cancer
  (type_of_cancer_id, name, dedicated_color, short_name, parent)
SELECT *
FROM (
  -- `tissue` is the frontend's synthetic root category; do not insert a
  -- database row with the same ID or it replaces that root in the tree.
  SELECT 'mixed' AS type_of_cancer_id, 'Mixed' AS name,
         '' AS dedicated_color, 'Mixed' AS short_name, 'tissue' AS parent
) AS seed
WHERE type_of_cancer_id NOT IN (SELECT type_of_cancer_id FROM type_of_cancer);

INSERT INTO cancer_study (
  cancer_study_id, cancer_study_identifier, type_of_cancer_id, name,
  description, public, groups, status, import_date, reference_genome_id
) VALUES
  (990001, 'msk_spectrum_tme_2022', 'mixed', 'SPECTRUM TME public WSI fixture',
   'Public SPECTRUM WSI fixture', 1, 'PUBLIC', 1, now(), 2),
  (990002, 'wsi_ci_study_b', 'mixed', 'WSI CI study B',
   'Authenticated WSI CI control', 0, 'PRIVATE', 1, now(), 2);

-- WSI-CI-NO-SLIDES-PATIENT is a known patient without any WSI resource rows:
-- the hierarchy endpoint must answer 200 with an empty sampleGroups list, not 404.
INSERT INTO patient (internal_id, stable_id, cancer_study_id) VALUES
  (990001, 'P-0055908', 990001),
  (990002, 'WSI-CI-B-PATIENT', 990002),
  (990003, 'WSI-CI-NO-SLIDES-PATIENT', 990001);

INSERT INTO sample (internal_id, stable_id, sample_type, patient_id) VALUES
  (990001, 'P-0055908-T01-IM6', 'Primary', 990001),
  (990002, 'WSI-CI-B-SAMPLE', 'Primary', 990002),
  (990003, 'WSI-CI-NO-SLIDES-SAMPLE', 'Primary', 990003);

-- Minimal public sample-list membership makes the fixture discoverable through
-- the normal study/patient API in addition to the WSI hierarchy endpoint.
INSERT INTO sample_list
  (list_id, stable_id, category, cancer_study_id, name, description)
VALUES (990001, 'msk_spectrum_tme_2022_all', 'all', 990001,
        'All SPECTRUM fixture samples', 'Public WSI fixture sample list');
INSERT INTO sample_list_list (list_id, sample_id) VALUES (990001, 990001), (990001, 990003);
INSERT INTO sample_list
  (list_id, stable_id, category, cancer_study_id, name, description)
VALUES (990002, 'wsi_ci_study_b_all', 'all', 990002,
        'All WSI CI control samples', 'Authenticated WSI CI control sample list');
INSERT INTO sample_list_list (list_id, sample_id) VALUES (990002, 990002);

-- The seven WSI_* slide-count attributes the Core converter merges into the
-- study's clinical sample and patient files. The slide-less patient/sample
-- carry NA in the files and therefore no rows here.
INSERT INTO clinical_attribute_meta
  (attr_id, display_name, description, datatype, patient_attribute, priority,
   cancer_study_id)
SELECT attr_id, display_name, description, 'NUMBER', patient_attribute, '1', study
FROM (
  SELECT 'WSI_SAMPLE_SLIDE_COUNT' AS attr_id, 'WSI Slides per Sample' AS display_name,
         'Associated pathology slide count for the sample.' AS description, 0 AS patient_attribute
  UNION ALL SELECT 'WSI_SAMPLE_PART_MATCHED_SLIDE_COUNT', 'WSI Slides per Sample, Part-matched',
         'Associated pathology slides matched to a specimen part.', 0
  UNION ALL SELECT 'WSI_SAMPLE_BLOCK_MATCHED_SLIDE_COUNT', 'WSI Slides per Sample, Block-matched',
         'Associated pathology slides matched to a specimen block.', 0
  UNION ALL SELECT 'WSI_PATIENT_SLIDE_COUNT', 'WSI Slides per Patient',
         'Associated pathology slide count for the patient.', 1
  UNION ALL SELECT 'WSI_PATIENT_PART_MATCHED_SLIDE_COUNT', 'WSI Slides per Patient, Part-matched',
         'Associated pathology slides matched to a specimen part for the patient.', 1
  UNION ALL SELECT 'WSI_PATIENT_BLOCK_MATCHED_SLIDE_COUNT', 'WSI Slides per Patient, Block-matched',
         'Associated pathology slides matched to a specimen block for the patient.', 1
  UNION ALL SELECT 'WSI_PATIENT_UNDATED_SLIDE_COUNT', 'WSI Undated Viewable Slides per Patient',
         'Viewable pathology slides without a procedure date, which the timeline does not show.', 1
) AS attrs
CROSS JOIN (SELECT arrayJoin([990001, 990002]) AS study) AS studies;

INSERT INTO clinical_sample (internal_id, attr_id, attr_value)
VALUES
  (990001, 'WSI_SAMPLE_SLIDE_COUNT', '2'),
  (990001, 'WSI_SAMPLE_PART_MATCHED_SLIDE_COUNT', '1'),
  (990001, 'WSI_SAMPLE_BLOCK_MATCHED_SLIDE_COUNT', '1'),
  (990002, 'WSI_SAMPLE_SLIDE_COUNT', '2'),
  (990002, 'WSI_SAMPLE_PART_MATCHED_SLIDE_COUNT', '1'),
  (990002, 'WSI_SAMPLE_BLOCK_MATCHED_SLIDE_COUNT', '1');

INSERT INTO clinical_patient (internal_id, attr_id, attr_value)
VALUES
  (990001, 'WSI_PATIENT_SLIDE_COUNT', '4'),
  (990001, 'WSI_PATIENT_PART_MATCHED_SLIDE_COUNT', '1'),
  (990001, 'WSI_PATIENT_BLOCK_MATCHED_SLIDE_COUNT', '1'),
  (990001, 'WSI_PATIENT_UNDATED_SLIDE_COUNT', '1'),
  (990002, 'WSI_PATIENT_SLIDE_COUNT', '3'),
  (990002, 'WSI_PATIENT_PART_MATCHED_SLIDE_COUNT', '1'),
  (990002, 'WSI_PATIENT_BLOCK_MATCHED_SLIDE_COUNT', '1'),
  (990002, 'WSI_PATIENT_UNDATED_SLIDE_COUNT', '0');

-- Studies no longer load a PATHOLOGY SLIDES clinical-event (timeline) file:
-- slide timing lives only in the resource METADATA (timeline_* fields), and the
-- frontend builds its pathology track from the WSI hierarchy.

-- WSI resources. Rows are the generated data_resource_sample.txt /
-- data_resource_patient.txt contents of both fixture studies, verbatim, with
-- fixed RESOURCE_DATA_IDs (the importer allocates them from seq_resource_data,
-- so tests must read the ids from the hierarchy response, never hard-code them).
-- Sample-matched (PART/BLOCK) slides are WSI_SAMPLE rows; unmatched slides are
-- WSI_PATIENT rows with SAMPLE_ID NULL. wsi_serving holds the only copy of the
-- source/thumbnail URLs and tile metadata; the generic resource table API must
-- never return it.
INSERT INTO resource_definition
  (resource_id, display_name, description, resource_type, open_by_default,
   priority, cancer_study_id, custom_metadata)
VALUES
  ('WSI_SAMPLE', 'Pathology slides', 'Whole-slide images matched to a sample', 'SAMPLE', 0, 1, 990001, NULL),
  ('WSI_PATIENT', 'Pathology slides', 'Whole-slide images not matched to a sample', 'PATIENT', 0, 1, 990001, NULL),
  ('WSI_SAMPLE', 'Pathology slides', 'Whole-slide images matched to a sample', 'SAMPLE', 0, 1, 990002, NULL),
  ('WSI_PATIENT', 'Pathology slides', 'Whole-slide images not matched to a sample', 'PATIENT', 0, 1, 990002, NULL);

-- A WHOLE_SLIDE_IMAGE row outside the WSI_SAMPLE/WSI_PATIENT resources, with a
-- complete serving object: the image-ID access lookup must never serve it.
INSERT INTO resource_definition
  (resource_id, display_name, description, resource_type, open_by_default,
   priority, cancer_study_id, custom_metadata)
VALUES
  ('WSI_CI_OTHER_SLIDES', 'Other slides', 'Slides outside the WSI resources', 'PATIENT', 0, 2, 990001, NULL);
INSERT INTO resource_data
  (RESOURCE_DATA_ID, RESOURCE_ID, CANCER_STUDY_ID, ENTITY_TYPE, PATIENT_ID,
   SAMPLE_ID, URL, DISPLAY_NAME, TYPE, METADATA)
VALUES
  (990199, 'WSI_CI_OTHER_SLIDES', 990001, 'PATIENT', 'P-0055908', NULL,
   'http://localhost:8080/wsi/patient/P-0055908?studyId=msk_spectrum_tme_2022&imageId=wsi-ci-other-slide',
   'wsi-ci-other-slide', 'WHOLE_SLIDE_IMAGE',
   '{"image_id":"wsi-ci-other-slide","can_serve_tiles":true,"wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/3020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"tile_size":256}}}');

INSERT INTO resource_data
  (RESOURCE_DATA_ID, RESOURCE_ID, CANCER_STUDY_ID, ENTITY_TYPE, PATIENT_ID,
   SAMPLE_ID, URL, DISPLAY_NAME, TYPE, METADATA)
VALUES
  (990101, 'WSI_SAMPLE', 990001, 'SAMPLE', 'P-0055908', 'P-0055908-T01-IM6',
   'http://localhost:8080/wsi/patient/P-0055908?studyId=msk_spectrum_tme_2022&imageId=3020726',
   '3020726', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"4","block_label":"4RO","block_number":"4","can_serve_tiles":true,"file_size_bytes":716956681,"image_id":"3020726","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"BLOCK","part_description":"Right fallopian tube and ovary","part_designator":"27","part_key":"27","part_number":"27","part_type":"FALLOPIAN TUBE AND OVARY","path_dx_title":"Right fallopian tube and ovary","reference_sample_id":"P-0055908-T01-IM6","slide_type":"H&E","specimen_key":"block::27::4","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"RECORDED","timeline_date_source":"RECORDED_PROCEDURE_DATE","timeline_date_status":"AVAILABLE","timeline_start_days":-17,"timepoint_source":"Recorded procedure date relative to first tumor sequencing","wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/3020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"mpp":{"x":0.499,"y":0.499},"objective_power":20,"tile_size":256,"vendor":"aperio"}}}'),
  (990102, 'WSI_SAMPLE', 990001, 'SAMPLE', 'P-0055908', 'P-0055908-T01-IM6',
   'http://localhost:8080/wsi/patient/P-0055908?studyId=msk_spectrum_tme_2022&imageId=3020691',
   '3020691', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"62","block_label":"1 RFIM","block_number":"62","can_serve_tiles":true,"file_size_bytes":538183815,"image_id":"3020691","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"PART","part_description":"Right fallopian tube and ovary","part_designator":"27","part_key":"27","part_number":"27","part_type":"FALLOPIAN TUBE AND OVARY","path_dx_title":"Right fallopian tube and ovary","reference_sample_id":"P-0055908-T01-IM6","slide_type":"H&E","specimen_key":"part::27","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"RECORDED","timeline_date_source":"RECORDED_PROCEDURE_DATE","timeline_date_status":"AVAILABLE","timeline_start_days":-17,"timepoint_source":"Recorded procedure date relative to first tumor sequencing","wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/3020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"mpp":{"x":0.499,"y":0.499},"objective_power":20,"tile_size":256,"vendor":"aperio"}}}'),
  (990103, 'WSI_PATIENT', 990001, 'PATIENT', 'P-0055908', NULL,
   'http://localhost:8080/wsi/patient/P-0055908?studyId=msk_spectrum_tme_2022&imageId=3020648',
   '3020648', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"4","block_label":"4RS","block_number":"4","can_serve_tiles":false,"file_size_bytes":1014457317,"image_id":"3020648","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"UNMATCHED","part_description":"Portion of small bowel and right colon with tumor","part_designator":"34","part_key":"34","part_number":"34","part_type":"SMALL BOWEL","path_dx_title":"Portion of small bowel and right colon with tumor","reference_sample_id":"P-0055908-T01-IM6","slide_type":"H&E","specimen_key":"unmatched::34::4","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"UNDATED","timeline_date_reason":"MISSING_PROCEDURE_DATE","timeline_date_source":"NO_VERIFIED_PROCEDURE_DATE","timeline_date_status":"MISSING_PROCEDURE_DATE","timepoint_source":"MISSING_PROCEDURE_DATE","wsi_serving":{}}'),
  (990104, 'WSI_PATIENT', 990001, 'PATIENT', 'P-0055908', NULL,
   'http://localhost:8080/wsi/patient/P-0055908?studyId=msk_spectrum_tme_2022&imageId=3020649',
   '3020649', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"5","block_label":"5RS","block_number":"5","can_serve_tiles":true,"file_size_bytes":1014457317,"image_id":"3020649","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"UNMATCHED","part_description":"Portion of small bowel and right colon with tumor","part_designator":"35","part_key":"35","part_number":"35","part_type":"SMALL BOWEL","path_dx_title":"Portion of small bowel and right colon with tumor","reference_sample_id":"P-0055908-T01-IM6","slide_type":"H&E","specimen_key":"unmatched::35::5","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"UNDATED","timeline_date_reason":"MISSING_PROCEDURE_DATE","timeline_date_source":"NO_VERIFIED_PROCEDURE_DATE","timeline_date_status":"MISSING_PROCEDURE_DATE","timepoint_source":"MISSING_PROCEDURE_DATE","wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/3020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"mpp":{"x":0.499,"y":0.499},"objective_power":20,"tile_size":256,"vendor":"aperio"}}}'),
  (990201, 'WSI_SAMPLE', 990002, 'SAMPLE', 'WSI-CI-B-PATIENT', 'WSI-CI-B-SAMPLE',
   'http://localhost:8080/wsi/patient/WSI-CI-B-PATIENT?studyId=wsi_ci_study_b&imageId=4020726',
   '4020726', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"4","block_label":"4RO","block_number":"4","can_serve_tiles":true,"file_size_bytes":716956681,"image_id":"4020726","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"BLOCK","part_description":"Right fallopian tube and ovary","part_designator":"27","part_key":"27","part_number":"27","part_type":"FALLOPIAN TUBE AND OVARY","path_dx_title":"Right fallopian tube and ovary","reference_sample_id":"WSI-CI-B-SAMPLE","slide_type":"H&E","specimen_key":"block::27::4","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"RECORDED","timeline_date_source":"RECORDED_PROCEDURE_DATE","timeline_date_status":"AVAILABLE","timeline_start_days":-17,"timepoint_source":"Recorded procedure date relative to first tumor sequencing","wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/4020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"mpp":{"x":0.499,"y":0.499},"objective_power":20,"tile_size":256,"vendor":"aperio"}}}'),
  (990202, 'WSI_SAMPLE', 990002, 'SAMPLE', 'WSI-CI-B-PATIENT', 'WSI-CI-B-SAMPLE',
   'http://localhost:8080/wsi/patient/WSI-CI-B-PATIENT?studyId=wsi_ci_study_b&imageId=4020691',
   '4020691', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"62","block_label":"1 RFIM","block_number":"62","can_serve_tiles":true,"file_size_bytes":538183815,"image_id":"4020691","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"PART","part_description":"Right fallopian tube and ovary","part_designator":"27","part_key":"27","part_number":"27","part_type":"FALLOPIAN TUBE AND OVARY","path_dx_title":"Right fallopian tube and ovary","reference_sample_id":"WSI-CI-B-SAMPLE","slide_type":"H&E","specimen_key":"part::27","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"RECORDED","timeline_date_source":"RECORDED_PROCEDURE_DATE","timeline_date_status":"AVAILABLE","timeline_start_days":-17,"timepoint_source":"Recorded procedure date relative to first tumor sequencing","wsi_serving":{"source_url":"file:///app/testdata/CMU-1-Small-Region.svs","thumbnail_content_type":"image/jpeg","thumbnail_height":232,"thumbnail_url":"file:///app/testdata/4020691.jpg","thumbnail_width":256,"tile_metadata_json":{"dimensions":{"height":2967,"width":2220},"level_dimensions":[{"height":2967,"width":2220}],"level_downsamples":[1.0],"levels":1,"max_zoom":4,"mpp":{"x":0.499,"y":0.499},"objective_power":20,"tile_size":256,"vendor":"aperio"}}}'),
  (990203, 'WSI_PATIENT', 990002, 'PATIENT', 'WSI-CI-B-PATIENT', NULL,
   'http://localhost:8080/wsi/patient/WSI-CI-B-PATIENT?studyId=wsi_ci_study_b&imageId=4020648',
   '4020648', 'WHOLE_SLIDE_IMAGE',
   '{"block_key":"4","block_label":"4RS","block_number":"4","can_serve_tiles":false,"file_size_bytes":1014457317,"image_id":"4020648","is_hne":true,"is_ihc":false,"magnification":"20x","match_level":"UNMATCHED","part_description":"Portion of small bowel and right colon with tumor","part_designator":"34","part_key":"34","part_number":"34","part_type":"SMALL BOWEL","path_dx_title":"Portion of small bowel and right colon with tumor","reference_sample_id":"WSI-CI-B-SAMPLE","slide_type":"H&E","specimen_key":"unmatched::34::4","stain_group":"H&E (Initial)","stain_name":"H&E, Initial","timeline_coordinate_system":"patient_first_tumor_sequencing_day_zero","timeline_date_kind":"UNDATED","timeline_date_reason":"MISSING_PROCEDURE_DATE","timeline_date_source":"NO_VERIFIED_PROCEDURE_DATE","timeline_date_status":"MISSING_PROCEDURE_DATE","timepoint_source":"MISSING_PROCEDURE_DATE","wsi_serving":{}}');
