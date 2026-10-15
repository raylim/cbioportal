package org.cbioportal.domain.wsi;

import java.util.Map;

/** Whole-slide-image counts for one patient in a study-view cohort. */
public record WsiStudySlidePatient(
    String studyId, String patientId, long slideCount, Map<String, Long> stainGroupCounts) {}
