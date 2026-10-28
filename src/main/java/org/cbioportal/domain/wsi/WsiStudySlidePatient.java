package org.cbioportal.domain.wsi;

import java.util.Map;

/**
 * Whole-slide-image counts for one patient in a study-view cohort: the listed slides, the samples
 * they are matched to, and the slides by stain group.
 */
public record WsiStudySlidePatient(
    String studyId,
    String patientId,
    long slideCount,
    long sampleCount,
    Map<String, Long> stainGroupCounts) {}
