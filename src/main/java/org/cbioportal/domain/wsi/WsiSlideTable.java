package org.cbioportal.domain.wsi;

import java.util.List;
import org.cbioportal.domain.resource.ResourceMetadataField;
import org.cbioportal.domain.resource.ResourceMetadataSchema;

/** The study slide table: every viewable slide of the study, as one generic resource table. */
public final class WsiSlideTable {

  /**
   * The resource id the generic resource table answers the study slide table under: one row per
   * slide the viewer can open, from both WSI_SAMPLE (matched to a sample) and WSI_PATIENT
   * (unmatched, no sample). It is not a stored resource; its rows come from
   * wsi_slide_table_derived, and WSI_SAMPLE and WSI_PATIENT stay ordinary resources.
   */
  public static final String RESOURCE_ID = "WSI_SLIDES";

  /** The resource whose definition (display name, description) the slide table uses. */
  public static final String DEFINITION_RESOURCE_ID = "WSI_SAMPLE";

  /**
   * The study slide table's columns, and its allowlist: a slide table row exposes these metadata
   * keys and no others. The slide key is the de-identified Slide ID that the viewer shows and slide
   * links already carry. Part and block are bare numbers and display_name (the "stain · part /
   * block" caption) is left empty, since either would repeat the columns. Specimen, part and block
   * keys, the reference sample id, file size and wsi_serving stay in the WSI_SAMPLE and WSI_PATIENT
   * rows.
   */
  public static final ResourceMetadataSchema SCHEMA =
      new ResourceMetadataSchema(
          1,
          List.of(
              new ResourceMetadataField(
                  "slide_key", "string", "Slide ID", "De-identified slide ID", true, true),
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
                  "Match",
                  "Whether the slide was matched to the sample by part or by block",
                  true,
                  false)));

  /** The study slide table's metadata keys, in column order. */
  public static final List<String> METADATA_KEYS = List.copyOf(SCHEMA.fieldsByKey().keySet());

  private WsiSlideTable() {}

  public static boolean isSlideTable(String resourceId) {
    return RESOURCE_ID.equals(resourceId);
  }

  public static boolean isMetadataKey(String key) {
    return key != null && METADATA_KEYS.contains(key);
  }
}
