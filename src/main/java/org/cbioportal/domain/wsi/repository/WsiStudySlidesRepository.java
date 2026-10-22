package org.cbioportal.domain.wsi.repository;

import java.util.List;
import java.util.Map;
import org.cbioportal.domain.studyview.StudyViewFilterContext;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideAttributeFacet;
import org.cbioportal.domain.wsi.WsiStudySlidesPage;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;

/** Lists the patients of a study-view cohort that have whole-slide images. */
public interface WsiStudySlidesRepository {

  /**
   * Returns one page of the cohort's patients with slides, ordered by study and patient ID.
   *
   * <p>A patient is in the cohort when any of their samples passes the study-view filter; all of
   * that patient's slides are then counted, including patient-level slides.
   */
  WsiStudySlidesPage getStudySlides(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query);

  /**
   * Returns, for each attribute, the listed patients per clinical value, most frequent first,
   * keeping at most {@code maxValues} values per attribute. Attributes without values are left out.
   */
  List<WsiStudySlideAttributeFacet> getAttributeFacets(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query,
      List<String> attributeIds,
      int maxValues);

  /** Returns the listed patients with a slide of each specimen match level. */
  Map<String, Long> getMatchLevelCounts(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query);
}
