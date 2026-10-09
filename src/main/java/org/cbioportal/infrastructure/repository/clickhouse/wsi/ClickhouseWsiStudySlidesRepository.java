package org.cbioportal.infrastructure.repository.clickhouse.wsi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cbioportal.domain.studyview.StudyViewFilterContext;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideAttributeFacet;
import org.cbioportal.domain.wsi.WsiStudySlideFacets.WsiStudySlideFacetValue;
import org.cbioportal.domain.wsi.WsiStudySlidePatient;
import org.cbioportal.domain.wsi.WsiStudySlidesPage;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;
import org.cbioportal.domain.wsi.repository.WsiStudySlidesRepository;
import org.springframework.stereotype.Repository;

@Repository
public class ClickhouseWsiStudySlidesRepository implements WsiStudySlidesRepository {

  private final ClickhouseWsiStudySlidesMapper mapper;

  public ClickhouseWsiStudySlidesRepository(ClickhouseWsiStudySlidesMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public WsiStudySlidesPage getStudySlides(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query) {
    Map<String, Object> totals = mapper.getCohortTotals(studyViewFilterContext, studyIds, query);
    long totalPatients = longValue(totals, "total_patients");
    long offset = (long) query.pageNumber() * query.pageSize();
    List<WsiStudySlidePatient> patients =
        offset < totalPatients
            ? mapper
                .getCohortPatients(
                    studyViewFilterContext, studyIds, query, query.pageSize(), offset)
                .stream()
                .map(
                    row ->
                        new WsiStudySlidePatient(
                            (String) row.get("study_id"),
                            (String) row.get("patient_id"),
                            longValue(row, "slide_count"),
                            stainGroupCounts(row)))
                .toList()
            : List.of();
    Long locatedIndex =
        totals != null && truthy(totals.get("located")) ? longValue(totals, "located_index") : null;
    return new WsiStudySlidesPage(
        totalPatients,
        longValue(totals, "total_slides"),
        stainGroupCounts(totals),
        locatedIndex,
        query.pageNumber(),
        query.pageSize(),
        patients);
  }

  @Override
  public List<WsiStudySlideAttributeFacet> getAttributeFacets(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query,
      List<String> attributeIds,
      int maxValues) {
    if (attributeIds.isEmpty()) {
      return List.of();
    }
    Map<String, List<WsiStudySlideFacetValue>> values = new LinkedHashMap<>();
    Map<String, Long> valueCounts = new LinkedHashMap<>();
    for (Map<String, Object> row :
        mapper.getCohortAttributeValueCounts(
            studyViewFilterContext, studyIds, query, attributeIds, maxValues)) {
      String attributeId = (String) row.get("attribute_id");
      values
          .computeIfAbsent(attributeId, id -> new java.util.ArrayList<>())
          .add(
              new WsiStudySlideFacetValue(
                  (String) row.get("value"), longValue(row, "patient_count")));
      valueCounts.put(attributeId, longValue(row, "value_count"));
    }
    return attributeIds.stream()
        .filter(values::containsKey)
        .map(
            id ->
                new WsiStudySlideAttributeFacet(
                    id, values.get(id), valueCounts.get(id) > values.get(id).size()))
        .toList();
  }

  @Override
  public Map<String, Long> getMatchLevelCounts(
      StudyViewFilterContext studyViewFilterContext,
      List<String> studyIds,
      WsiStudySlidesQuery query) {
    Map<String, Long> counts = new LinkedHashMap<>();
    WsiStudySlidesQuery.MATCH_LEVELS.forEach(level -> counts.put(level, 0L));
    for (Map<String, Object> row :
        mapper.getCohortMatchLevelCounts(studyViewFilterContext, studyIds, query)) {
      counts.put((String) row.get("match_level"), longValue(row, "patient_count"));
    }
    return counts;
  }

  private static Map<String, Long> stainGroupCounts(Map<String, Object> row) {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("H&E", longValue(row, "hne"));
    counts.put("IHC", longValue(row, "ihc"));
    counts.put("Other", longValue(row, "other"));
    counts.put("Unknown", longValue(row, "unknown"));
    return counts;
  }

  private static long longValue(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value instanceof Number number && number.longValue() != 0;
  }
}
