package org.cbioportal.domain.wsi;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.cbioportal.domain.studyview.StudyViewFilterContext;
import org.cbioportal.domain.studyview.StudyViewService;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideAttributeFacet;
import org.cbioportal.domain.wsi.repository.WsiStudySlidesRepository;
import org.cbioportal.legacy.web.parameter.ClinicalDataFilter;
import org.cbioportal.legacy.web.parameter.DataFilterValue;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.junit.Test;

public class WsiStudySlidesServiceTest {

  private final StudyViewService studyViewService = mock(StudyViewService.class);
  private final WsiStudySlidesRepository repository = mock(WsiStudySlidesRepository.class);
  private final WsiStudySlidesService service =
      new WsiStudySlidesService(studyViewService, repository);

  private static WsiStudySlideAttributeFacet facet(String attributeId) {
    return new WsiStudySlideAttributeFacet(attributeId, List.of(), false);
  }

  @Test
  public void countsEachFacetWithoutItsOwnFilter() {
    StudyViewFilter filter = new StudyViewFilter();
    filter.setStudyIds(List.of("study"));
    ClinicalDataFilter cancerType = new ClinicalDataFilter();
    cancerType.setAttributeId("CANCER_TYPE");
    DataFilterValue breast = new DataFilterValue();
    breast.setValue("Breast Cancer");
    cancerType.setValues(List.of(breast));
    filter.setClinicalDataFilters(List.of(cancerType));

    StudyViewFilterContext withCancerType = mock(StudyViewFilterContext.class);
    StudyViewFilterContext withoutCancerType = mock(StudyViewFilterContext.class);
    when(studyViewService.buildStudyViewFilterContext(any()))
        .thenAnswer(
            invocation -> {
              StudyViewFilter built = invocation.getArgument(0);
              return built.getClinicalDataFilters().isEmpty() ? withoutCancerType : withCancerType;
            });
    when(repository.getAttributeFacets(
            eq(withCancerType), any(), any(), eq(List.of("SEX")), eq(10)))
        .thenReturn(List.of(facet("SEX")));
    when(repository.getAttributeFacets(
            eq(withoutCancerType), any(), any(), eq(List.of("CANCER_TYPE")), eq(10)))
        .thenReturn(List.of(facet("CANCER_TYPE")));
    when(repository.getMatchLevelCounts(eq(withCancerType), any(), any()))
        .thenReturn(Map.of("PART", 1L));

    WsiStudySlidesQuery query =
        new WsiStudySlidesQuery(true, List.of(), List.of("PART"), null, null, null, 0, 1);
    WsiStudySlideFacets facets =
        service.getStudySlideFacets(filter, query, List.of("CANCER_TYPE", "SEX"), 10);

    // Requested order is kept.
    assertEquals(
        List.of("CANCER_TYPE", "SEX"),
        facets.attributes().stream().map(WsiStudySlideAttributeFacet::attributeId).toList());
    assertEquals(Map.of("PART", 1L), facets.matchLevels());
    // Match levels are counted without the match-level filter.
    verify(repository)
        .getMatchLevelCounts(
            eq(withCancerType), eq(List.of("study")), argThat(q -> q.matchLevels().isEmpty()));
    // The caller's filter is not changed.
    assertEquals(1, filter.getClinicalDataFilters().size());
  }
}
