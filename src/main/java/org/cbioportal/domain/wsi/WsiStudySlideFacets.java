package org.cbioportal.domain.wsi;

import java.util.List;
import java.util.Map;

/**
 * Filter options for a study-view cohort's patients with slides: per clinical value and per
 * specimen match level, how many listed patients a filter on it would keep. Each facet ignores its
 * own filter.
 */
public record WsiStudySlideFacets(
    List<WsiStudySlideAttributeFacet> attributes, Map<String, Long> matchLevels) {

  /** One clinical attribute's most frequent values; {@code truncated} when some were left out. */
  public record WsiStudySlideAttributeFacet(
      String attributeId, List<WsiStudySlideFacetValue> values, boolean truncated) {}

  public record WsiStudySlideFacetValue(String value, long patientCount) {}
}
