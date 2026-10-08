package org.cbioportal.infrastructure.repository.clickhouse.wsi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.cbioportal.domain.studyview.StudyViewFilterFactory;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideAttributeFacet;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideFacetValue;
import org.cbioportal.domain.wsi.WsiStudySlidePatient;
import org.cbioportal.domain.wsi.WsiStudySlidesPage;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;
import org.cbioportal.infrastructure.repository.clickhouse.AbstractTestcontainers;
import org.cbioportal.infrastructure.repository.clickhouse.config.MyBatisConfig;
import org.cbioportal.legacy.web.parameter.SampleIdentifier;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@Import({MyBatisConfig.class, ClickhouseWsiStudySlidesRepository.class})
@DataJpaTest
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = AbstractTestcontainers.Initializer.class)
public class ClickhouseWsiStudySlidesRepositoryTest {

  private static final String COHORT_STUDY = "wsi_cohort_study";

  @Autowired private ClickhouseWsiStudySlidesRepository repository;

  @Test
  public void countsEveryPatientWithSlidesInTheStudy() {
    WsiStudySlidesPage page = fetch(studies(COHORT_STUDY), query());

    assertEquals(3, page.totalPatients());
    assertEquals(5, page.totalSlides());
    assertEquals(4, page.totalViewableSlides());
    assertEquals(stainCounts(2, 1, 1, 1), page.stainGroupTotals());
    assertNull(page.locatedIndex());
    assertEquals(
        List.of("COHORT-A", "COHORT-B", "OTHER-D"),
        page.patients().stream().map(WsiStudySlidePatient::patientId).toList());

    WsiStudySlidePatient cohortA = page.patients().get(0);
    assertEquals(COHORT_STUDY, cohortA.studyId());
    assertEquals(3, cohortA.slideCount());
    assertEquals(2, cohortA.viewableSlideCount());
    assertEquals(stainCounts(1, 1, 0, 1), cohortA.stainGroupCounts());
    // A slide type outside H&E/IHC/Unknown counts as Other, as the viewer classifies it.
    assertEquals(stainCounts(0, 0, 1, 0), page.patients().get(1).stainGroupCounts());
  }

  @Test
  public void aSelectedSampleBringsItsSlidesAndItsPatientsUnmatchedSlides() {
    // As in the study slide table: COHORT-A-2's slide and COHORT-A's unmatched slide, but not
    // COHORT-A-1's slide, whose sample is outside the cohort.
    StudyViewFilter filter = new StudyViewFilter();
    SampleIdentifier sample = new SampleIdentifier();
    sample.setStudyId(COHORT_STUDY);
    sample.setSampleId("COHORT-A-2");
    filter.setSampleIdentifiers(List.of(sample));

    WsiStudySlidesPage page = fetch(filter, query());

    assertEquals(1, page.totalPatients());
    assertEquals(2, page.totalSlides());
    assertEquals("COHORT-A", page.patients().get(0).patientId());
    assertEquals(2, page.patients().get(0).slideCount());
  }

  @Test
  public void searchesPatientIds() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(false, List.of(), List.of(), "COHORT", null, null, 0, 50));

    assertEquals(2, page.totalPatients());
    assertEquals(
        List.of("COHORT-A", "COHORT-B"),
        page.patients().stream().map(WsiStudySlidePatient::patientId).toList());
  }

  @Test
  public void filtersByStainGroupButKeepsEveryStainGroupTotal() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(false, List.of("IHC"), List.of(), null, null, null, 0, 50));

    assertEquals(1, page.totalPatients());
    assertEquals(1, page.totalSlides());
    assertEquals(0, page.totalViewableSlides());
    assertEquals(stainCounts(2, 1, 1, 1), page.stainGroupTotals());
    assertEquals(1, page.patients().get(0).slideCount());
    assertEquals(stainCounts(0, 1, 0, 0), page.patients().get(0).stainGroupCounts());
  }

  @Test
  public void pagesInPatientOrder() {
    WsiStudySlidesPage second =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(false, List.of(), List.of(), null, null, null, 1, 2));
    assertEquals(3, second.totalPatients());
    assertEquals(
        List.of("OTHER-D"),
        second.patients().stream().map(WsiStudySlidePatient::patientId).toList());

    WsiStudySlidesPage pastTheEnd =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(false, List.of(), List.of(), null, null, null, 5, 2));
    assertEquals(3, pastTheEnd.totalPatients());
    assertTrue(pastTheEnd.patients().isEmpty());
  }

  @Test
  public void locatesAPatientInTheOrderedList() {
    WsiStudySlidesPage located =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(
                false, List.of(), List.of(), null, COHORT_STUDY, "OTHER-D", 0, 50));
    assertEquals(Long.valueOf(2), located.locatedIndex());

    WsiStudySlidesPage withoutSlides =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(
                false, List.of(), List.of(), null, COHORT_STUDY, "COHORT-C", 0, 50));
    assertNull(withoutSlides.locatedIndex());
  }

  @Test
  public void ordersMultipleStudiesAndCountsOnlyWsiResources() {
    WsiStudySlidesPage page = fetch(studies(COHORT_STUDY, "wsi_test_study"), query());

    assertEquals(4, page.totalPatients());
    WsiStudySlidePatient last = page.patients().get(3);
    assertEquals("wsi_test_study", last.studyId());
    assertEquals("WSI-PATIENT", last.patientId());
    // The OTHER_SLIDES whole-slide image is not a WSI resource, and the legacy WSI row without a
    // slide_key cannot be listed or served: neither is counted.
    assertEquals(2, last.slideCount());
    assertEquals(1, last.viewableSlideCount());
  }

  @Test
  public void countsOnlyViewableSlidesWhenAsked() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(true, List.of(), List.of(), null, null, null, 0, 50));

    assertEquals(3, page.totalPatients());
    assertEquals(4, page.totalSlides());
    assertEquals(4, page.totalViewableSlides());
    // COHORT-A's IHC slide cannot be served, so no IHC slide is counted.
    assertEquals(stainCounts(2, 0, 1, 1), page.stainGroupTotals());
    WsiStudySlidePatient cohortA = page.patients().get(0);
    assertEquals(2, cohortA.slideCount());
    assertEquals(stainCounts(1, 0, 0, 1), cohortA.stainGroupCounts());

    // A patient whose only slides cannot be served is not listed.
    WsiStudySlidesPage withTestStudy =
        fetch(
            studies("wsi_snapshot_study"),
            new WsiStudySlidesQuery(true, List.of(), List.of(), null, null, null, 0, 50));
    assertEquals(0, withTestStudy.totalPatients());
    assertTrue(withTestStudy.patients().isEmpty());
  }

  @Test
  public void searchesSampleIdsIgnoringCase() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(false, List.of(), List.of(), "a-2", null, null, 0, 50));

    assertEquals(1, page.totalPatients());
    assertEquals("COHORT-A", page.patients().get(0).patientId());
    // Only the slide of the matching sample.
    assertEquals(1, page.patients().get(0).slideCount());
  }

  @Test
  public void filtersByMatchLevel() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(
                false, List.of(), List.of("UNMATCHED"), null, null, null, 0, 50));

    assertEquals(1, page.totalPatients());
    assertEquals("COHORT-A", page.patients().get(0).patientId());
    assertEquals(1, page.totalSlides());
  }

  @Test
  public void countsPatientsPerClinicalValueAndMatchLevel() {
    StudyViewFilter filter = studies(COHORT_STUDY);
    List<String> studyIds = List.copyOf(filter.getUniqueStudyIds());
    var context = StudyViewFilterFactory.make(filter, null, studyIds, null);

    List<WsiStudySlideAttributeFacet> facets =
        repository.getAttributeFacets(
            context, studyIds, query(), List.of("CANCER_TYPE", "SEX", "MISSING"), 200);

    assertEquals(List.of("CANCER_TYPE", "SEX"), facets.stream().map(f -> f.attributeId()).toList());
    // COHORT-C's Breast Cancer sample has no slides; ties are ordered by value.
    assertEquals(
        List.of(
            new WsiStudySlideFacetValue("Breast Cancer", 1),
            new WsiStudySlideFacetValue("Colorectal Cancer", 1),
            new WsiStudySlideFacetValue("Melanoma", 1)),
        facets.get(0).values());
    assertEquals(false, facets.get(0).truncated());
    assertEquals(
        List.of(new WsiStudySlideFacetValue("Female", 2), new WsiStudySlideFacetValue("Male", 1)),
        facets.get(1).values());

    List<WsiStudySlideAttributeFacet> truncated =
        repository.getAttributeFacets(context, studyIds, query(), List.of("CANCER_TYPE"), 1);
    assertEquals(1, truncated.get(0).values().size());
    assertTrue(truncated.get(0).truncated());

    // A slide without a sample is unmatched; a matched slide without a level is block-matched.
    assertEquals(
        Map.of("PART", 1L, "BLOCK", 3L, "UNMATCHED", 1L),
        repository.getMatchLevelCounts(context, studyIds, query()));
  }

  @Test
  public void returnsNothingForAStudyWithoutSlides() {
    WsiStudySlidesPage page = fetch(studies("wsi_empty_hierarchy_study"), query());

    assertEquals(0, page.totalPatients());
    assertEquals(0, page.totalSlides());
    assertEquals(stainCounts(0, 0, 0, 0), page.stainGroupTotals());
    assertTrue(page.patients().isEmpty());
  }

  private WsiStudySlidesPage fetch(StudyViewFilter filter, WsiStudySlidesQuery query) {
    List<String> studyIds = List.copyOf(filter.getUniqueStudyIds());
    return repository.getStudySlides(
        StudyViewFilterFactory.make(filter, null, studyIds, null), studyIds, query);
  }

  private static StudyViewFilter studies(String... studyIds) {
    StudyViewFilter filter = new StudyViewFilter();
    filter.setStudyIds(List.of(studyIds));
    return filter;
  }

  private static WsiStudySlidesQuery query() {
    return new WsiStudySlidesQuery(false, List.of(), List.of(), null, null, null, 0, 50);
  }

  private static Map<String, Long> stainCounts(long hne, long ihc, long other, long unknown) {
    return Map.of("H&E", hne, "IHC", ihc, "Other", other, "Unknown", unknown);
  }
}
