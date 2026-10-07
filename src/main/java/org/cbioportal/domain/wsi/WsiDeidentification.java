package org.cbioportal.domain.wsi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.cbioportal.domain.resource.ResourceMetadataField;
import org.cbioportal.domain.resource.ResourceMetadataSchema;

/** Shared de-identification rules for the browser-facing WSI contract (wsi-serving-v5). */
public final class WsiDeidentification {

  /** Opaque per-slide key: the first 32 hex chars of a salted SHA-256 of the image identifier. */
  public static final Pattern SLIDE_KEY = Pattern.compile("^[0-9a-f]{32}$");

  /**
   * The resource_data resources that hold one row per slide. Their rows carry the private {@code
   * wsi_serving} object; the WSI hierarchy and access endpoints read them in full.
   */
  public static final Set<String> WSI_RESOURCE_IDS = Set.of("WSI_SAMPLE", "WSI_PATIENT");

  /**
   * The one WSI resource the generic resource table serves, as the study-level slide table: one row
   * per slide matched to a sample. WSI_PATIENT stays out of the generic resource table.
   */
  public static final String STUDY_TABLE_RESOURCE_ID = "WSI_SAMPLE";

  /**
   * The study slide table's columns. This is an allowlist, not decoration: a WSI_SAMPLE row exposes
   * these metadata keys and no others to rows, search, filters, sorts, facets and ranges. Part and
   * block are bare numbers: the hierarchy's "Specimen N" / "Block N" labels would repeat the column
   * header in every cell. For the same reason the table leaves display_name empty (the slide's
   * "stain · specimen / block" caption), which hides the Details column. The table lists only
   * slides the viewer can open (can_serve_tiles), in its rows and in every count and facet. The
   * opaque slide, specimen, part and block keys, the reference sample id, file size and timing
   * provenance stay with the WSI hierarchy and access endpoints, and wsi_serving stays private
   * everywhere.
   */
  public static final ResourceMetadataSchema STUDY_TABLE_SCHEMA =
      new ResourceMetadataSchema(
          1,
          List.of(
              new ResourceMetadataField("stain_name", "string", "Stain", null, true, true),
              new ResourceMetadataField(
                  "stain_group", "string", "Stain Group", "H&E, IHC, Other or Unknown", true, true),
              new ResourceMetadataField(
                  "magnification", "string", "Magnification", null, true, true),
              new ResourceMetadataField(
                  "part_number",
                  "number",
                  "Part",
                  "Part number within the pathology case",
                  true,
                  true),
              new ResourceMetadataField(
                  "block_number", "number", "Block", "Block number within the part", true, true),
              new ResourceMetadataField(
                  "match_level",
                  "string",
                  "Matched At",
                  "Whether the slide was matched to the sample by specimen part or by block",
                  true,
                  false),
              new ResourceMetadataField(
                  "timepoint_source",
                  "string",
                  "Timepoint Source",
                  "How the slide's procedure date was established",
                  true,
                  false),
              new ResourceMetadataField(
                  "timeline_start_days",
                  "number",
                  "Days From First Sequencing",
                  "Procedure date relative to the patient's first tumor sequencing",
                  true,
                  false)));

  /** The study slide table's metadata keys, in column order. Read by ResourceDataMapper.xml. */
  public static final List<String> STUDY_TABLE_METADATA_KEYS =
      STUDY_TABLE_SCHEMA.fields().stream().map(ResourceMetadataField::key).toList();

  private static final Map<String, ResourceMetadataField> STUDY_TABLE_FIELDS =
      STUDY_TABLE_SCHEMA.fields().stream()
          .collect(Collectors.toMap(ResourceMetadataField::key, Function.identity()));

  private WsiDeidentification() {}

  public static boolean isSlideKey(String value) {
    return value != null && SLIDE_KEY.matcher(value).matches();
  }

  public static boolean isWsiResourceId(String resourceId) {
    return resourceId != null && WSI_RESOURCE_IDS.contains(resourceId);
  }

  /** WSI resources the generic resource table does not serve at all. */
  public static boolean isHiddenFromResourceTable(String resourceId) {
    return isWsiResourceId(resourceId) && !isStudyTableResource(resourceId);
  }

  public static boolean isStudyTableResource(String resourceId) {
    return STUDY_TABLE_RESOURCE_ID.equals(resourceId);
  }

  public static boolean isStudyTableMetadataKey(String key) {
    return key != null && STUDY_TABLE_FIELDS.containsKey(key);
  }

  public static boolean isNumericStudyTableMetadataKey(String key) {
    ResourceMetadataField field = key == null ? null : STUDY_TABLE_FIELDS.get(key);
    return field != null && "number".equals(field.type());
  }
}
