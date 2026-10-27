package org.cbioportal.domain.wsi;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.cbioportal.domain.resource.ResourceMetadataField;
import org.cbioportal.domain.resource.ResourceMetadataSchema;

/** Shared formats of the browser-facing WSI contract (wsi-serving-v6). */
public final class WsiContract {

  /** Opaque per-slide key: the first 12 hex chars of a salted SHA-256 of the image identifier. */
  public static final Pattern SLIDE_KEY = Pattern.compile("^[0-9a-f]{12}$");

  /**
   * The resource rows that hold one row per slide. Their rows carry the private {@code wsi_serving}
   * object; the WSI hierarchy and access endpoints read them in full.
   */
  public static final Set<String> WSI_RESOURCE_IDS = Set.of("WSI_SAMPLE", "WSI_PATIENT");

  /** Sealed slide source: unpadded base64url of nonce(12) || ciphertext || tag(16). */
  public static final Pattern SEALED_SOURCE = Pattern.compile("^[A-Za-z0-9_-]+$");

  /** Nonce, GCM tag and at least one byte of ciphertext. */
  public static final int MIN_SEALED_SOURCE_BYTES = 12 + 16 + 1;

  public static final int MAX_SEALED_SOURCE_LENGTH = 4096;

  /**
   * The one WSI resource the generic resource table serves, as the study-level slide table: one row
   * per slide the viewer can open, the same slides the Pathology Slides viewer lists. Slides
   * matched to a sample come from WSI_SAMPLE; unmatched ones come from WSI_PATIENT, with no sample.
   * WSI_PATIENT as a resource of its own stays out of the generic resource table.
   */
  public static final String STUDY_TABLE_RESOURCE_ID = "WSI_SAMPLE";

  /**
   * The study slide table's columns, and its allowlist: a WSI_SAMPLE row exposes these metadata
   * keys and no others. The slide key is the de-identified Slide ID that the viewer shows and slide
   * links already carry. Part and block are bare numbers and display_name (the "stain · part /
   * block" caption) is left empty, since either would repeat the columns. Specimen, part and block
   * keys, the reference sample id, file size and wsi_serving stay with the WSI hierarchy and access
   * endpoints.
   */
  public static final ResourceMetadataSchema STUDY_TABLE_SCHEMA =
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
  public static final List<String> STUDY_TABLE_METADATA_KEYS =
      List.copyOf(STUDY_TABLE_SCHEMA.fieldsByKey().keySet());

  private WsiContract() {}

  public static boolean isSlideKey(String value) {
    return value != null && SLIDE_KEY.matcher(value).matches();
  }

  /**
   * Whether {@code value} has the shape of a sealed slide source. cBioPortal cannot decrypt it; the
   * tile server authenticates it against the slide key.
   */
  public static boolean isSealedSource(String value) {
    if (value == null
        || value.length() > MAX_SEALED_SOURCE_LENGTH
        || !SEALED_SOURCE.matcher(value).matches()) {
      return false;
    }
    try {
      return Base64.getUrlDecoder().decode(value).length >= MIN_SEALED_SOURCE_BYTES;
    } catch (IllegalArgumentException exception) {
      return false;
    }
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
    return key != null && STUDY_TABLE_METADATA_KEYS.contains(key);
  }
}
