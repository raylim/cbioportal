package org.cbioportal.domain.wsi;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One slide in the browser-facing hierarchy. Slides are addressed only by the opaque {@code
 * slideKey}; slide barcodes and resource-data row identifiers are never exposed, and the pathology
 * image identifier is not stored in cBioPortal at all.
 *
 * <p>The {@code procedureDate*} and {@code timepointSource} fields are optional slide timing,
 * relative to the patient's first tumor-sequencing day zero (never an absolute date). They are null
 * when the slide's resource metadata carries no timing keys.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WsiSlide(
    String slideKey,
    String stainName,
    String stainGroup,
    boolean isHne,
    boolean isIhc,
    String magnification,
    Long fileSizeBytes,
    boolean canServeTiles,
    String slideType,
    String sampleId,
    String matchLevel,
    String specimenKey,
    Integer procedureDateDays,
    String timepointSource,
    String procedureDateKind,
    String procedureDateSource,
    String procedureDateReason,
    String procedureDateStatus,
    String procedureCoordinateSystem) {}
