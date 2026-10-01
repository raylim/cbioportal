package org.cbioportal.domain.wsi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One page of the patients with whole-slide images in a study-view cohort, with cohort totals.
 *
 * <p>{@code stainGroupTotals} ignores the stain-group filter so callers can show a count for every
 * stain group; the other totals and the page honour every filter. {@code locatedIndex} is the
 * zero-based position of the requested patient in the full ordered list, or {@code null} when that
 * patient was not requested or is not in the list.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WsiStudySlidesPage(
    long totalPatients,
    long totalSlides,
    long totalViewableSlides,
    Map<String, Long> stainGroupTotals,
    Long locatedIndex,
    int pageNumber,
    int pageSize,
    List<WsiStudySlidePatient> patients) {}
