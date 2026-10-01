package org.cbioportal.domain.wsi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cbioportal.domain.studyview.StudyViewService;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideAttributeFacet;
import org.cbioportal.domain.wsi.repository.WsiStudySlidesRepository;
import org.cbioportal.legacy.model.AlterationFilter;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.springframework.stereotype.Service;

/** Lists a study-view cohort's patients with whole-slide images, and their filter options. */
@Service
public class WsiStudySlidesService {

  private static final ObjectMapper FILTER_COPIER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final StudyViewService studyViewService;
  private final WsiStudySlidesRepository repository;

  public WsiStudySlidesService(
      StudyViewService studyViewService, WsiStudySlidesRepository repository) {
    this.studyViewService = studyViewService;
    this.repository = repository;
  }

  public WsiStudySlidesPage getStudySlides(
      StudyViewFilter studyViewFilter, WsiStudySlidesQuery query) {
    StudyViewFilter filter = copy(studyViewFilter);
    List<String> studyIds = List.copyOf(filter.getUniqueStudyIds());
    return repository.getStudySlides(
        studyViewService.buildStudyViewFilterContext(filter), studyIds, query);
  }

  /**
   * Returns the clinical-value and match-level filter options. Each option is counted without its
   * own filter, so a filtered attribute still offers its other values: attributes without a
   * clinical filter share one query, and each filtered attribute gets its own.
   */
  public WsiStudySlideFacets getStudySlideFacets(
      StudyViewFilter studyViewFilter,
      WsiStudySlidesQuery query,
      List<String> attributeIds,
      int maxValues) {
    List<String> studyIds = List.copyOf(studyViewFilter.getUniqueStudyIds());
    Set<String> filtered =
        studyViewFilter.getClinicalDataFilters() == null
            ? Set.of()
            : studyViewFilter.getClinicalDataFilters().stream()
                .map(f -> f.getAttributeId())
                .collect(Collectors.toSet());

    List<WsiStudySlideAttributeFacet> facets = new ArrayList<>();
    List<String> unfiltered = attributeIds.stream().filter(id -> !filtered.contains(id)).toList();
    if (!unfiltered.isEmpty()) {
      facets.addAll(
          repository.getAttributeFacets(
              studyViewService.buildStudyViewFilterContext(copy(studyViewFilter)),
              studyIds,
              query,
              unfiltered,
              maxValues));
    }
    for (String attributeId : attributeIds) {
      if (filtered.contains(attributeId)) {
        StudyViewFilter withoutOwn = copy(studyViewFilter);
        withoutOwn.setClinicalDataFilters(
            withoutOwn.getClinicalDataFilters().stream()
                .filter(f -> !attributeId.equals(f.getAttributeId()))
                .collect(Collectors.toList()));
        facets.addAll(
            repository.getAttributeFacets(
                studyViewService.buildStudyViewFilterContext(withoutOwn),
                studyIds,
                query,
                List.of(attributeId),
                maxValues));
      }
    }
    facets.sort(Comparator.comparingInt(f -> attributeIds.indexOf(f.attributeId())));

    Map<String, Long> matchLevels =
        repository.getMatchLevelCounts(
            studyViewService.buildStudyViewFilterContext(copy(studyViewFilter)),
            studyIds,
            query.withoutMatchLevels());
    return new WsiStudySlideFacets(facets, matchLevels);
  }

  /** A copy the filter-context factory can normalize without touching the caller's filter. */
  private static StudyViewFilter copy(StudyViewFilter studyViewFilter) {
    StudyViewFilter copy = FILTER_COPIER.convertValue(studyViewFilter, StudyViewFilter.class);
    if (copy.getAlterationFilter() == null) {
      copy.setAlterationFilter(new AlterationFilter());
    }
    return copy;
  }
}
