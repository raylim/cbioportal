package org.cbioportal.infrastructure.repository.clickhouse.wsi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.cbioportal.domain.studyview.StudyViewFilterFactory;
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
    // COHORT-A's IHC slide cannot be served, so it is neither listed nor counted.
    assertEquals(4, page.totalSlides());
    assertEquals(stainCounts(2, 0, 1, 1), page.stainGroupTotals());
    assertNull(page.locatedIndex());
    assertEquals(
        List.of("COHORT-A", "COHORT-B", "OTHER-D"),
        page.patients().stream().map(WsiStudySlidePatient::patientId).toList());

    WsiStudySlidePatient cohortA = page.patients().get(0);
    assertEquals(COHORT_STUDY, cohortA.studyId());
    assertEquals(2, cohortA.slideCount());
    assertEquals(stainCounts(1, 0, 0, 1), cohortA.stainGroupCounts());
    // A slide type outside H&E/IHC/Unknown counts as Other, as the viewer classifies it.
    assertEquals(stainCounts(0, 0, 1, 0), page.patients().get(1).stainGroupCounts());
  }

  @Test
  public void aSelectedSampleBringsItsSlidesAndItsPatientsUnmatchedSlides() {
    // As in the study slide table: COHORT-A's unmatched slide, but not COHORT-A-1's slide, whose
    // sample is outside the cohort (COHORT-A-2's own slide cannot be served).
    StudyViewFilter filter = new StudyViewFilter();
    SampleIdentifier sample = new SampleIdentifier();
    sample.setStudyId(COHORT_STUDY);
    sample.setSampleId("COHORT-A-2");
    filter.setSampleIdentifiers(List.of(sample));

    WsiStudySlidesPage page = fetch(filter, query());

    assertEquals(1, page.totalPatients());
    assertEquals(1, page.totalSlides());
    assertEquals("COHORT-A", page.patients().get(0).patientId());
    assertEquals(stainCounts(0, 0, 0, 1), page.patients().get(0).stainGroupCounts());
  }

  @Test
  public void searchesPatientIds() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), "COHORT", null, null, 0, 50));

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
            new WsiStudySlidesQuery(List.of("H&E"), List.of(), null, null, null, 0, 50));

    assertEquals(2, page.totalPatients());
    assertEquals(2, page.totalSlides());
    assertEquals(stainCounts(2, 0, 1, 1), page.stainGroupTotals());
    assertEquals(1, page.patients().get(0).slideCount());
    assertEquals(stainCounts(1, 0, 0, 0), page.patients().get(0).stainGroupCounts());
  }

  @Test
  public void pagesInPatientOrder() {
    WsiStudySlidesPage second =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), null, null, null, 1, 2));
    assertEquals(3, second.totalPatients());
    assertEquals(
        List.of("OTHER-D"),
        second.patients().stream().map(WsiStudySlidePatient::patientId).toList());

    WsiStudySlidesPage pastTheEnd =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), null, null, null, 5, 2));
    assertEquals(3, pastTheEnd.totalPatients());
    assertTrue(pastTheEnd.patients().isEmpty());
  }

  @Test
  public void locatesAPatientInTheOrderedList() {
    WsiStudySlidesPage located =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), null, COHORT_STUDY, "OTHER-D", 0, 50));
    assertEquals(Long.valueOf(2), located.locatedIndex());

    WsiStudySlidesPage withoutSlides =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), null, COHORT_STUDY, "COHORT-C", 0, 50));
    assertNull(withoutSlides.locatedIndex());
  }

  @Test
  public void ordersMultipleStudiesAndCountsOnlyWsiResources() {
    WsiStudySlidesPage page = fetch(studies(COHORT_STUDY, "wsi_test_study"), query());

    assertEquals(4, page.totalPatients());
    WsiStudySlidePatient last = page.patients().get(3);
    assertEquals("wsi_test_study", last.studyId());
    assertEquals("WSI-PATIENT", last.patientId());
    // The OTHER_SLIDES whole-slide image is not a WSI resource, and neither the legacy WSI row
    // without a slide_key nor the patient-level slide that cannot be served is listed: none is
    // counted.
    assertEquals(1, last.slideCount());
  }

  @Test
  public void searchesSampleIdsIgnoringCase() {
    WsiStudySlidesPage page =
        fetch(
            studies(COHORT_STUDY),
            new WsiStudySlidesQuery(List.of(), List.of(), "a-1", null, null, 0, 50));

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
            new WsiStudySlidesQuery(List.of(), List.of("UNMATCHED"), null, null, null, 0, 50));

    assertEquals(1, page.totalPatients());
    assertEquals("COHORT-A", page.patients().get(0).patientId());
    assertEquals(1, page.totalSlides());
  }

  @Test
  public void returnsNothingForAStudyWithoutViewableSlides() {
    // wsi_snapshot_study's only slide cannot be served, so its patient is not listed.
    WsiStudySlidesPage page = fetch(studies("wsi_snapshot_study"), query());

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
    return new WsiStudySlidesQuery(List.of(), List.of(), null, null, null, 0, 50);
  }

  private static Map<String, Long> stainCounts(long hne, long ihc, long other, long unknown) {
    return Map.of("H&E", hne, "IHC", ihc, "Other", other, "Unknown", unknown);
  }
}
