package org.cbioportal.domain.wsi;

import java.util.List;

/**
 * Options for listing a cohort's patients with whole-slide images.
 *
 * @param stainGroups stain groups to keep; empty keeps all
 * @param matchLevels specimen match levels to keep ({@code PART}, {@code BLOCK}, {@code
 *     UNMATCHED}); empty keeps all
 * @param search keeps slides whose patient or sample ID contains this, ignoring case; null keeps
 *     all
 * @param locateStudyId with {@code locatePatientId}, a patient whose list position is returned
 * @param locatePatientId see {@code locateStudyId}
 * @param pageNumber zero-based page
 * @param pageSize patients per page
 */
public record WsiStudySlidesQuery(
    List<String> stainGroups,
    List<String> matchLevels,
    String search,
    String locateStudyId,
    String locatePatientId,
    int pageNumber,
    int pageSize) {

  /** The stain groups slides are counted under, classified as the viewer classifies them. */
  public static final List<String> STAIN_GROUPS = List.of("H&E", "IHC", "Other", "Unknown");

  /**
   * How a slide's specimen is matched to a sequenced sample, as the viewer classifies it: a slide
   * without a sample is unmatched, and a matched slide without a level is block-matched.
   */
  public static final List<String> MATCH_LEVELS = List.of("PART", "BLOCK", "UNMATCHED");

  /** The same query without the match-level filter, for match-level counts. */
  public WsiStudySlidesQuery withoutMatchLevels() {
    return new WsiStudySlidesQuery(
        stainGroups, List.of(), search, locateStudyId, locatePatientId, pageNumber, pageSize);
  }
}
