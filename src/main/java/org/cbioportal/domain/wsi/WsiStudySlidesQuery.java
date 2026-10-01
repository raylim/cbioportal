package org.cbioportal.domain.wsi;

import java.util.List;

/**
 * Options for listing a cohort's patients with whole-slide images.
 *
 * @param stainGroups stain groups to keep; empty keeps all
 * @param patientIdPrefix keeps patients whose ID starts with this; null keeps all
 * @param locateStudyId with {@code locatePatientId}, a patient whose list position is returned
 * @param locatePatientId see {@code locateStudyId}
 * @param pageNumber zero-based page
 * @param pageSize patients per page
 */
public record WsiStudySlidesQuery(
    List<String> stainGroups,
    String patientIdPrefix,
    String locateStudyId,
    String locatePatientId,
    int pageNumber,
    int pageSize) {

  /** The stain groups slides are counted under, classified as the viewer classifies them. */
  public static final List<String> STAIN_GROUPS = List.of("H&E", "IHC", "Other", "Unknown");
}
