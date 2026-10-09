package org.cbioportal.domain.wsi;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.cbioportal.domain.resource.ResourceMetadataField;
import org.cbioportal.domain.resource.ResourceMetadataSchema;

/** Shared de-identification rules for the browser-facing WSI contract (wsi-serving-v6). */
public final class WsiDeidentification {

  /** Opaque per-slide key: the first 32 hex chars of a salted SHA-256 of the image identifier. */
  public static final Pattern SLIDE_KEY = Pattern.compile("^[0-9a-f]{32}$");

  /**
   * The resource_data resources that hold one row per slide. Their rows carry the private {@code
   * wsi_serving} object; the WSI hierarchy and access endpoints read them in full.
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
   * The study slide table's columns. This is an allowlist, not decoration: a WSI_SAMPLE row exposes
   * these metadata keys and no others to rows, search, filters, sorts, facets and ranges. Slide
   * timing (procedure dates) is not served yet; it arrives with slides on the patient Summary
   * timeline. Part and block are bare numbers: the hierarchy's "Specimen N" / "Block N" labels
   * would repeat the column header in every cell. For the same reason the table leaves display_name
   * empty (the slide's "stain · specimen / block" caption), which hides the Details column. The
   * table lists only slides the viewer can open (can_serve_tiles), in its rows and in every count
   * and facet. The opaque slide, specimen, part and block keys, the reference sample id and file
   * size stay with the WSI hierarchy and access endpoints, and wsi_serving stays private
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
                  false)));

  /** The study slide table's metadata keys, in column order. */
  public static final List<String> STUDY_TABLE_METADATA_KEYS =
      STUDY_TABLE_SCHEMA.fields().stream().map(ResourceMetadataField::key).toList();

  private static final Map<String, ResourceMetadataField> STUDY_TABLE_FIELDS =
      STUDY_TABLE_SCHEMA.fields().stream()
          .collect(Collectors.toMap(ResourceMetadataField::key, Function.identity()));

  private static final Pattern ABSOLUTE_DATE =
      Pattern.compile(
          "(?<!\\d)(?:19|20)\\d{2}[-_/](?:0?[1-9]|1[0-2])[-_/](?:0?[1-9]|[12]\\d|3[01])(?!\\d)");
  private static final Pattern MONTH_FIRST_DATE =
      Pattern.compile(
          "(?<!\\d)(?:0?[1-9]|1[0-2])[-_/](?:0?[1-9]|[12]\\d|3[01])[-_/](?:19|20)\\d{2}(?!\\d)");
  private static final Pattern DAY_FIRST_DATE =
      Pattern.compile(
          "(?<!\\d)(?:0?[1-9]|[12]\\d|3[01])[-_/](?:0?[1-9]|1[0-2])[-_/](?:19|20)\\d{2}(?!\\d)");
  private static final Pattern NAMED_MONTH_DATE =
      Pattern.compile(
          "(?i)(?<![a-z0-9])(?:(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|"
              + "may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|"
              + "nov(?:ember)?|dec(?:ember)?)\\s+(?:0?[1-9]|[12]\\d|3[01])(?:st|nd|rd|th)?"
              + "(?:,)?\\s+(?:19|20)\\d{2}|(?:0?[1-9]|[12]\\d|3[01])[-/\\s]+"
              + "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
              + "jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|"
              + "dec(?:ember)?)[-/\\s]+(?:19|20)\\d{2})(?![a-z0-9])");
  private static final Pattern COMPACT_DATE = Pattern.compile("(?<!\\d)(?:19|20)\\d{6}(?!\\d)");
  private static final Pattern LABELLED_MRN =
      Pattern.compile(
          "(?i)\\b(?:mrn|medical[ _-]?record(?:[ _-]?number)?)\\b\\s*[:=#-]?\\s*\\d{4,}");

  private WsiDeidentification() {}

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

  /**
   * Whether free text served to the browser contains a labelled medical record number or an
   * absolute date (ISO, month-first, day-first, named-month or compact YYYYMMDD). WSI metadata that
   * does fails closed.
   */
  public static boolean containsIdentifyingText(String value) {
    return value != null
        && (LABELLED_MRN.matcher(value).find()
            || ABSOLUTE_DATE.matcher(value).find()
            || MONTH_FIRST_DATE.matcher(value).find()
            || DAY_FIRST_DATE.matcher(value).find()
            || NAMED_MONTH_DATE.matcher(value).find()
            || COMPACT_DATE.matcher(value).find());
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
