package org.cbioportal.domain.wsi;

import java.util.List;
import org.cbioportal.domain.studyview.StudyViewService;
import org.cbioportal.domain.wsi.repository.WsiStudySlidesRepository;
import org.cbioportal.legacy.model.AlterationFilter;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.springframework.stereotype.Service;

/** Lists a study-view cohort's patients with whole-slide images. */
@Service
public class WsiStudySlidesService {

  private final StudyViewService studyViewService;
  private final WsiStudySlidesRepository repository;

  public WsiStudySlidesService(
      StudyViewService studyViewService, WsiStudySlidesRepository repository) {
    this.studyViewService = studyViewService;
    this.repository = repository;
  }

  public WsiStudySlidesPage getStudySlides(
      StudyViewFilter studyViewFilter, WsiStudySlidesQuery query) {
    // The filter is the request body's, used only here, so it is normalized in place, as the
    // other study-view endpoints do.
    if (studyViewFilter.getAlterationFilter() == null) {
      studyViewFilter.setAlterationFilter(new AlterationFilter());
    }
    List<String> studyIds = List.copyOf(studyViewFilter.getUniqueStudyIds());
    return repository.getStudySlides(
        studyViewService.buildStudyViewFilterContext(studyViewFilter), studyIds, query);
  }
}
