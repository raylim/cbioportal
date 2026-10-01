package org.cbioportal.infrastructure.repository.clickhouse.wsi;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.cbioportal.domain.studyview.StudyViewFilterContext;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;

/** MyBatis access to cohort-level WSI slide counts. */
public interface ClickhouseWsiStudySlidesMapper {

  Map<String, Object> getCohortTotals(
      @Param("studyViewFilterContext") StudyViewFilterContext studyViewFilterContext,
      @Param("studyIds") List<String> studyIds,
      @Param("query") WsiStudySlidesQuery query);

  List<Map<String, Object>> getCohortPatients(
      @Param("studyViewFilterContext") StudyViewFilterContext studyViewFilterContext,
      @Param("studyIds") List<String> studyIds,
      @Param("query") WsiStudySlidesQuery query,
      @Param("limit") int limit,
      @Param("offset") long offset);

  List<Map<String, Object>> getCohortAttributeValueCounts(
      @Param("studyViewFilterContext") StudyViewFilterContext studyViewFilterContext,
      @Param("studyIds") List<String> studyIds,
      @Param("query") WsiStudySlidesQuery query,
      @Param("attributeIds") List<String> attributeIds,
      @Param("maxValues") int maxValues);

  List<Map<String, Object>> getCohortMatchLevelCounts(
      @Param("studyViewFilterContext") StudyViewFilterContext studyViewFilterContext,
      @Param("studyIds") List<String> studyIds,
      @Param("query") WsiStudySlidesQuery query);
}
